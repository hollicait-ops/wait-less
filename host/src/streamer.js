// streamer.js — FFmpeg screen capture + H.264 UDP sender + input receiver
//
// FFmpeg captures the desktop via gdigrab, encodes to H.264 Annex B, and
// the output is fragmented into UDP packets sent to the client.
//
// UDP framing (8-byte header per packet):
//   [0..3]  frame_id   uint32 BE — monotonic frame counter
//   [4..5]  frag_idx   uint16 BE — 0-based fragment index within frame
//   [6..7]  frag_count uint16 BE — total fragments in this frame
// Payload: up to MAX_PAYLOAD bytes of Annex B H.264 NAL data.
//
// Input events are received on INPUT_PORT as UTF-8 JSON datagrams.

const { spawn } = require('child_process');
const dgram = require('dgram');

const VIDEO_PORT = 9000;
const INPUT_PORT = 9001;
const MAX_PAYLOAD = 1392; // 1500 MTU - 20 IP - 8 UDP - 8 frame header = 1464, conservative 1392
const HEADER_SIZE = 8;

let ffmpeg = null;
let videoSocket = null;
let inputSocket = null;
let frameId = 0;

/**
 * Start FFmpeg capture and UDP streaming.
 *
 * @param {object} opts
 * @param {string}   opts.clientIp   — IP address to stream video to
 * @param {number}  [opts.videoPort] — UDP port on the client (default VIDEO_PORT)
 * @param {number}  [opts.inputPort] — UDP port to listen for input events (default INPUT_PORT)
 * @param {function} opts.onInput    — called with parsed input event objects
 * @param {function} opts.onLog      — called with status/log strings
 */
function startStreamer({ clientIp, videoPort = VIDEO_PORT, inputPort = INPUT_PORT, onInput, onLog }) {
  if (ffmpeg) {
    onLog('Streamer already running — stopping first');
    stopStreamer();
  }

  frameId = 0;

  // Video send socket (ephemeral local port)
  videoSocket = dgram.createSocket('udp4');
  videoSocket.on('error', (err) => onLog(`[udp-video] error: ${err.message}`));

  // Input receive socket
  inputSocket = dgram.createSocket('udp4');
  inputSocket.on('error', (err) => onLog(`[udp-input] error: ${err.message}`));
  inputSocket.on('message', (msg) => {
    try {
      const event = JSON.parse(msg.toString('utf8'));
      if (onInput && event && typeof event === 'object') onInput(event);
    } catch {
      // Ignore malformed datagrams
    }
  });
  inputSocket.bind(inputPort, () => onLog(`[udp-input] listening on port ${inputPort}`));

  // FFmpeg command: gdigrab → H.264 baseline → Annex B on stdout
  // -tune zerolatency: disables lookahead, rc-lookahead, and sliced threads,
  //   so each frame is flushed to output immediately.
  // keyint=60:min-keyint=60:scenecut=0: IDR every 60 frames (1 s at 60 fps),
  //   no scene-cut detection so frag_count is predictable.
  // bframes=0: no B-frames — baseline profile enforces this but explicit.
  const ffmpegArgs = [
    '-f', 'gdigrab',
    '-framerate', '60',
    '-i', 'desktop',
    '-vf', 'scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2',
    '-c:v', 'libx264',
    '-preset', 'ultrafast',
    '-tune', 'zerolatency',
    '-profile:v', 'baseline',
    '-level', '4.1',
    '-pix_fmt', 'yuv420p',
    // Explicit BT.709 metadata so the decoder uses the correct color matrix.
    // Without these, decoders may guess the wrong primaries and apply the wrong
    // YUV-to-RGB conversion, causing a chroma shift especially in dark areas.
    '-colorspace', 'bt709',
    '-color_primaries', 'bt709',
    '-color_trc', 'bt709',
    '-color_range', 'tv',
    // chroma-qp-offset=-4: reduce chroma quantization aggressiveness by 4 steps.
    // ultrafast over-quantizes UV in flat dark areas, leaving adjacent DCT blocks
    // alternating slightly above/below neutral chroma (128), which renders as
    // alternating magenta/green bands. A small negative offset gives UV channels
    // more bitrate at minimal overall cost.
    '-x264-params', 'keyint=30:min-keyint=30:scenecut=0:bframes=0:sliced-threads=0:chroma-qp-offset=-4',
    '-f', 'h264',
    'pipe:1',
  ];

  ffmpeg = spawn('ffmpeg', ffmpegArgs, { stdio: ['ignore', 'pipe', 'pipe'] });

  ffmpeg.on('error', (err) => {
    onLog(`[ffmpeg] spawn error: ${err.message} — is ffmpeg on PATH?`);
  });

  ffmpeg.stderr.on('data', (data) => {
    // Log only the first line of each stderr chunk to avoid noise
    const line = data.toString().split('\n').find(l => l.trim().length > 0);
    if (line) onLog(`[ffmpeg] ${line.trim()}`);
  });

  ffmpeg.on('exit', (code, signal) => {
    onLog(`[ffmpeg] process exited — code=${code} signal=${signal}`);
    ffmpeg = null;
  });

  const parser = createNalParser((frameData) => {
    sendFrame(frameData, clientIp, videoPort);
  });

  ffmpeg.stdout.on('data', (chunk) => parser.push(chunk));

  onLog(`[streamer] started — streaming to ${clientIp}:${videoPort}`);
}

function stopStreamer() {
  if (ffmpeg) {
    ffmpeg.kill('SIGTERM');
    ffmpeg = null;
  }
  if (videoSocket) {
    videoSocket.close();
    videoSocket = null;
  }
  if (inputSocket) {
    inputSocket.close();
    inputSocket = null;
  }
}

// ---------------------------------------------------------------------------
// NAL parser — buffers Annex B chunks, emits one complete frame at a time
// ---------------------------------------------------------------------------

// H.264 NAL unit types relevant for frame boundary detection
const NAL_IDR_SLICE = 5;
const NAL_NON_IDR_SLICE = 1;

function createNalParser(onFrame) {
  let buf = Buffer.alloc(0);
  // Accumulated NALs for the current frame (as raw NAL bodies, no start code)
  let frameNals = [];
  // Whether the current accumulated frame contains a slice NAL
  let frameHasSlice = false;

  function findStartCode(b, from) {
    for (let i = from; i < b.length - 3; i++) {
      if (b[i] === 0 && b[i + 1] === 0) {
        if (b[i + 2] === 0 && i + 3 < b.length && b[i + 3] === 1) return { pos: i, scLen: 4 };
        if (b[i + 2] === 1) return { pos: i, scLen: 3 };
      }
    }
    return null;
  }

  function nalUnitType(nalBody) {
    return nalBody.length > 0 ? (nalBody[0] & 0x1f) : 0;
  }

  // Check if a VCL NAL is the first slice of a new access unit.
  // first_mb_in_slice is the first exp-golomb value in the slice header;
  // when it's 0 the exp-golomb encoding is a single '1' bit, so the MSB
  // of the first RBSP byte (nalBody[1]) is set.
  function isFirstSliceInFrame(nalBody) {
    return nalBody.length >= 2 && (nalBody[1] & 0x80) !== 0;
  }

  function flushFrame() {
    if (frameNals.length === 0) return;
    // Rebuild as Annex B: 00 00 00 01 + nal body for each NAL
    const parts = frameNals.map(nal => Buffer.concat([Buffer.from([0, 0, 0, 1]), nal]));
    onFrame(Buffer.concat(parts));
    frameNals = [];
    frameHasSlice = false;
  }

  return {
    push(chunk) {
      buf = Buffer.concat([buf, chunk]);

      let offset = 0;
      while (true) {
        const sc = findStartCode(buf, offset);
        if (!sc) break;

        const nalBodyStart = sc.pos + sc.scLen;
        const next = findStartCode(buf, nalBodyStart + 1);
        if (!next) {
          // Keep from sc.pos onwards for the next push
          buf = buf.slice(sc.pos);
          return;
        }

        const nalBody = buf.slice(nalBodyStart, next.pos);
        const type = nalUnitType(nalBody);

        // A new access unit starts when we see a VCL NAL with first_mb_in_slice == 0
        // (i.e. the first slice of a new frame). Continuation slices (first_mb != 0)
        // belong to the current frame and must NOT trigger a flush.
        if ((type === NAL_NON_IDR_SLICE || type === NAL_IDR_SLICE) && frameHasSlice && isFirstSliceInFrame(nalBody)) {
          const carryOver = [];
          while (frameNals.length > 0) {
            const lastType = nalUnitType(frameNals[frameNals.length - 1]);
            if (lastType === NAL_NON_IDR_SLICE || lastType === NAL_IDR_SLICE) break;
            carryOver.unshift(frameNals.pop());
          }
          flushFrame();
          frameNals.push(...carryOver);
        }

        frameNals.push(nalBody);
        if (type === NAL_NON_IDR_SLICE || type === NAL_IDR_SLICE) {
          frameHasSlice = true;
        }

        buf = buf.slice(next.pos);
        offset = 0;
      }
    },
  };
}

// ---------------------------------------------------------------------------
// UDP framing — split frame into ≤MAX_PAYLOAD chunks with 8-byte headers
// ---------------------------------------------------------------------------

function sendFrame(frameData, ip, port) {
  const id = frameId & 0xFFFFFFFF;
  frameId = (frameId + 1) >>> 0;

  const fragCount = Math.ceil(frameData.length / MAX_PAYLOAD);

  for (let i = 0; i < fragCount; i++) {
    const payloadStart = i * MAX_PAYLOAD;
    const payloadEnd = Math.min(payloadStart + MAX_PAYLOAD, frameData.length);
    const payloadLen = payloadEnd - payloadStart;

    const packet = Buffer.allocUnsafe(HEADER_SIZE + payloadLen);
    packet.writeUInt32BE(id, 0);
    packet.writeUInt16BE(i, 4);
    packet.writeUInt16BE(fragCount, 6);
    frameData.copy(packet, HEADER_SIZE, payloadStart, payloadEnd);

    if (videoSocket) videoSocket.send(packet, port, ip);
    // Send fragment 0 twice: losing it makes the entire frame unrecoverable
    // since the reassembler needs all fragments. Duplicate cost is ~1% bandwidth.
    if (i === 0 && videoSocket) videoSocket.send(packet, port, ip);
  }
}

module.exports = { startStreamer, stopStreamer, VIDEO_PORT, INPUT_PORT };
