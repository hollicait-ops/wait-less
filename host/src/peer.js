// peer.js — WebRTC peer connection management (renderer process)
// LAN-only: no STUN/TURN needed since both peers are on the same subnet.

// Matches H.264 Constrained Baseline profile-level-id values (42e0xx / 4200xx).
// This profile uses a single well-defined YUV colour matrix and is hardware-
// decoded on every Android device, avoiding colour shifts from encoder/decoder
// disagreement on colour space when High/Main profiles are negotiated.
//
// We reorder rather than remove non-baseline PTs: removing PTs from a munged
// offer causes Chromium to reject setLocalDescription because its internal
// codec state still references the removed entries.
const H264_CONSTRAINED_BASELINE_REGEX = /profile-level-id=(42e0|4200)/i;

// Chromium sends per-frame colour space metadata via this RTP header extension.
// Some versions of the libwebrtc Android library negotiate the extension but
// misapply it (treating full-range BT.709 content as limited-range BT.601),
// producing a green cast in dark areas. Removing it from the offer forces both
// sides to use consistent H.264 stream-level defaults instead.
const COLOR_SPACE_EXT_URI = 'http://www.webrtc.org/experiments/rtp-hdrext/color-space';

function stripColorSpaceExtmap(sdp) {
  const lines = sdp.split('\r\n');
  let videoSection = false;
  let extId = null;

  for (const line of lines) {
    if (line.startsWith('m=video')) videoSection = true;
    else if (line.startsWith('m=')) videoSection = false;
    if (!videoSection) continue;
    if (line.includes(COLOR_SPACE_EXT_URI)) {
      extId = line.match(/a=extmap:(\d+)/)?.[1];
      break;
    }
  }

  if (!extId) return sdp;

  videoSection = false;
  return lines.filter(line => {
    if (line.startsWith('m=video')) videoSection = true;
    else if (line.startsWith('m=')) videoSection = false;
    if (!videoSection) return true;
    return !line.startsWith(`a=extmap:${extId} `);
  }).join('\r\n');
}

function preferH264(sdp) {
  const lines = sdp.split('\r\n');
  let videoSection = false;
  const allH264Pts = [];
  const baselinePts = new Set();

  for (const line of lines) {
    if (line.startsWith('m=video')) videoSection = true;
    else if (line.startsWith('m=')) videoSection = false;
    if (!videoSection) continue;

    if (/a=rtpmap:\d+ H264/i.test(line)) {
      const pt = line.match(/a=rtpmap:(\d+)/)[1];
      allH264Pts.push(pt);
    }
    if (/a=fmtp:/.test(line) && H264_CONSTRAINED_BASELINE_REGEX.test(line)) {
      const pt = line.match(/a=fmtp:(\d+)/)[1];
      baselinePts.add(pt);
    }
  }

  if (allH264Pts.length === 0) return sdp;

  // Within H264, put Constrained Baseline variants first so the remote peer
  // prefers them. All PTs are preserved — no lines are removed.
  const orderedH264 = [
    ...allH264Pts.filter(pt => baselinePts.has(pt)),
    ...allH264Pts.filter(pt => !baselinePts.has(pt)),
  ];

  return lines.map(line => {
    if (!line.startsWith('m=video')) return line;
    const parts = line.split(' ');
    const pts = parts.slice(3);
    const h264 = orderedH264.filter(pt => pts.includes(pt));
    const rest = pts.filter(pt => !allH264Pts.includes(pt));
    return `${parts[0]} ${parts[1]} ${parts[2]} ${[...h264, ...rest].join(' ')}`;
  }).join('\r\n');
}

async function startWebRTC(stream) {
  const pc = new RTCPeerConnection({ iceServers: [] });

  // Hint to the encoder that this is screen content, not a camera feed.
  // 'detail' mode preserves sharp edges and accurate colours; the default
  // motion-optimised mode applies camera-style YUV encoding that shifts hues.
  stream.getVideoTracks().forEach(track => { track.contentHint = 'detail'; });

  stream.getTracks().forEach(track => pc.addTrack(track, stream));

  // Register peer connection before sending the offer so that the incoming
  // SDP answer and ICE candidates aren't silently dropped by the signaling
  // handler (which guards on peerConnection being non-null).
  window.setPeerConnection(pc);

  pc.onicecandidate = ({ candidate }) => {
    if (candidate) {
      window.sendSignaling({ type: 'ice-candidate', candidate });
    }
  };

  pc.onconnectionstatechange = () => {
    const state = pc.connectionState;
    if (state === 'connected') {
      window.updateStreamStatus('Streaming');
      // Raise the video bitrate so the encoder doesn't aggressively quantise
      // chroma in flat dark areas, which causes a green cast. 12 Mbps is well
      // within LAN budget and avoids visible chroma drift in uniform regions.
      const sender = pc.getSenders().find(s => s.track?.kind === 'video');
      if (sender) {
        const params = sender.getParameters();
        if (params.encodings.length > 0) {
          params.encodings[0].maxBitrate = 12_000_000;
          sender.setParameters(params).catch(err => console.warn('setParameters failed:', err));
        }
      }
    } else if (state === 'disconnected' || state === 'failed') {
      window.updateStreamStatus('Disconnected - peer lost');
    }
  };

  const dc = pc.createDataChannel('input');
  dc.onmessage = ({ data }) => {
    let parsed;
    try { parsed = JSON.parse(data); } catch { return; }
    if (!parsed || typeof parsed !== 'object') return;
    window.streambridge.sendInputEvent(parsed);
  };

  const offer = await pc.createOffer();
  const mungedSdp = preferH264(offer.sdp);
  await pc.setLocalDescription({ type: offer.type, sdp: mungedSdp });
  window.sendSignaling({ type: offer.type, sdp: mungedSdp });

  return pc;
}

// Allow preferH264 to be required by Node.js tests (peer.js runs in the
// renderer where module is undefined, so this is a no-op in the browser).
if (typeof module !== 'undefined') module.exports = { preferH264, stripColorSpaceExtmap };
