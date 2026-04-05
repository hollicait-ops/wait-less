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

const { spawn, execSync } = require('child_process');
const dgram = require('dgram');

const VIDEO_PORT = 9000;
const INPUT_PORT = 9001;
const MAX_PAYLOAD = 1392; // 1500 MTU - 20 IP - 8 UDP - 8 frame header = 1464, conservative 1392
const HEADER_SIZE = 8;

let ffmpeg = null;
let videoSocket = null;
let inputSocket = null;
let frameId = 0;

// Cached encoder detection result: null = not checked, string = encoder name
let detectedEncoder = null;

/**
 * Detect the best available H.264 encoder by probing FFmpeg.
 * Prefers h264_nvenc (NVIDIA GPU) for lower latency, falls back to libx264.
 */
function detectEncoder() {
  if (detectedEncoder) return detectedEncoder;
  try {
    const output = execSync('ffmpeg -encoders -hide_banner 2>&1', { encoding: 'utf8', timeout: 5000 });
    if (output.includes('h264_nvenc')) {
      detectedEncoder = 'h264_nvenc';
    } else {
      detectedEncoder = 'libx264';
    }
  } catch {
    detectedEncoder = 'libx264';
  }
  return detectedEncoder;
}

/**
 * Build encoder-specific FFmpeg args.
 * Both paths produce H.264 Annex B with BT.709 color metadata and AUD NALs.
 */
function buildEncoderArgs(encoder) {
  // Explicit BT.709 metadata so the decoder uses the correct color matrix.
  // Without these, decoders may guess the wrong primaries and apply the wrong
  // YUV-to-RGB conversion, causing a chroma shift especially in dark areas.
  const colorArgs = [
    '-colorspace', 'bt709',
    '-color_primaries', 'bt709',
    '-color_trc', 'bt709',
    '-color_range', 'tv',
  ];

  if (encoder === 'h264_nvenc') {
    // NVENC hardware encoder — ~2ms encode latency, no frame pipeline.
    // -preset p1: fastest NVENC preset (was "llhp" in older drivers).
    // -tune ull: ultra-low-latency mode — disables B-frames, lookahead, etc.
    // -rc cbr: constant bitrate for predictable packet sizes.
    // -cbr true: enforce CBR (NVENC-specific).
    // -b:v 15M: 15 Mbps target — enough for 1080p60 without starving dark areas.
    // -aud 1: emit Access Unit Delimiter NALs (same as libx264 aud=1).
    // -forced-idr 1 + -g 30: IDR every 30 frames for loss recovery.
    return [
      '-c:v', 'h264_nvenc',
      '-preset', 'p1',
      '-tune', 'ull',
      '-profile:v', 'baseline',
      '-level', '4.1',
      '-pix_fmt', 'yuv420p',
      ...colorArgs,
      '-rc', 'cbr',
      '-b:v', '15M',
      '-g', '30',
      '-forced-idr', '1',
      '-aud', '1',
    ];
  }

  // libx264 software encoder fallback.
  // -tune zerolatency enables sliced-threads (intra-frame parallelism with
  //   zero inter-frame delay), rc-lookahead=0, sync-lookahead=0, and no mbtree.
  //   Do NOT override sliced-threads=0 — that falls back to frame-level threading
  //   which adds ~threads*16ms of pipeline latency.
  // chroma-qp-offset=-4: reduce chroma quantization aggressiveness by 4 steps.
  //   ultrafast over-quantizes UV in flat dark areas, leaving adjacent DCT blocks
  //   alternating slightly above/below neutral chroma (128), which renders as
  //   alternating magenta/green bands.
  // aud=1: emit AUD before each frame for immediate parser flush.
  // keyint=30: IDR every 30 frames (0.5s at 60fps) for fast recovery from loss.
  // slices=4: 4-way intra-frame parallelism; more slices = more parallelism but
  //   more boundary artifacts; 4 is a good balance.
  return [
    '-c:v', 'libx264',
    '-preset', 'ultrafast',
    '-tune', 'zerolatency',
    '-profile:v', 'baseline',
    '-level', '4.1',
    '-pix_fmt', 'yuv420p',
    ...colorArgs,
    '-x264-params', 'keyint=30:min-keyint=30:scenecut=0:bframes=0:slices=4:chroma-qp-offset=-4:aud=1',
  ];
}

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
  // Encoder selection: h264_nvenc (NVIDIA GPU) if available, else libx264.
  // Both produce identical Annex B H.264 output — the client is codec-agnostic.
  // -fflags nobuffer: skip input buffering on the demuxer side.
  // -flush_packets 1: flush each muxer packet to stdout immediately.
  const encoder = detectEncoder();
  onLog(`[streamer] using encoder: ${encoder}`);

  const ffmpegArgs = [
    '-fflags', 'nobuffer',
    '-thread_queue_size', '2',
    '-f', 'gdigrab',
    '-framerate', '60',
    '-i', 'desktop',
    '-vf', 'scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2',
    ...buildEncoderArgs(encoder),
    '-flush_packets', '1',
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

const NAL_AUD = 9; // Access Unit Delimiter

function createNalParser(onFrame) {
  let buf = Buffer.alloc(0);
  // Accumulated NAL bodies for the current frame (no start codes)
  let frameNals = [];

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

  function flushFrame() {
    if (frameNals.length === 0) return;
    // Rebuild as Annex B: 00 00 00 01 + nal body for each NAL
    const parts = frameNals.map(nal => Buffer.concat([Buffer.from([0, 0, 0, 1]), nal]));
    onFrame(Buffer.concat(parts));
    frameNals = [];
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
          buf = buf.slice(sc.pos);
          return;
        }

        const nalBody = buf.slice(nalBodyStart, next.pos);
        const type = nalUnitType(nalBody);

        if (type === NAL_AUD) {
          // AUD (type 9) is emitted by the encoder at the very start of each new
          // access unit, before any slice data for that frame is written. Flushing
          // here means we ship frame N as soon as frame N+1's encoding begins,
          // saving ~one encode cycle vs waiting for the next frame's first slice.
          // The AUD itself carries no picture data and is not forwarded.
          flushFrame();
        } else {
          frameNals.push(nalBody);
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
