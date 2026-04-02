// peer.js — WebRTC peer connection management (renderer process)
// LAN-only: no STUN/TURN needed since both peers are on the same subnet.

// Target profile: H.264 Constrained Baseline (profile-level-id prefix 42e0
// or 4200). This profile is hardware-decoded on every Android device and uses
// a single well-defined YUV colour matrix, avoiding the colour shifts seen
// when High/Main profiles are negotiated and encoder/decoder disagree on the
// colour space transform.
const H264_CONSTRAINED_BASELINE_REGEX = /profile-level-id=(42e0|4200)/i;

function preferH264(sdp) {
  const lines = sdp.split('\r\n');
  let videoSection = false;
  const allH264Pts = [];
  const baselinePts = [];

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
      baselinePts.push(pt);
    }
  }

  // Use Constrained Baseline if available; fall back to all H.264 variants.
  const preferredPts = baselinePts.length > 0 ? baselinePts : allH264Pts;
  if (preferredPts.length === 0) return sdp;

  // Remove payload types for non-preferred H.264 variants and their fmtp/rtcp-fb lines.
  const droppedPts = allH264Pts.filter(pt => !preferredPts.includes(pt));

  return lines
    .filter(line => {
      if (droppedPts.length === 0) return true;
      const ptMatch = line.match(/^a=(?:rtpmap|fmtp|rtcp-fb):(\d+)/);
      return !(ptMatch && droppedPts.includes(ptMatch[1]));
    })
    .map(line => {
      if (!line.startsWith('m=video')) return line;
      const parts = line.split(' ');
      const pts = parts.slice(3);
      const preferred = pts.filter(pt => preferredPts.includes(pt));
      const rest = pts.filter(pt => !preferredPts.includes(pt) && !droppedPts.includes(pt));
      return `${parts[0]} ${parts[1]} ${parts[2]} ${[...preferred, ...rest].join(' ')}`;
    })
    .join('\r\n');
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
if (typeof module !== 'undefined') module.exports = { preferH264 };
