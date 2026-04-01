// peer.js — WebRTC peer connection management (renderer process)
// LAN-only: no STUN/TURN needed since both peers are on the same subnet.

function preferH264(sdp) {
  const lines = sdp.split('\r\n');
  let videoSection = false;
  const h264Pts = [];

  for (const line of lines) {
    if (line.startsWith('m=video')) videoSection = true;
    else if (line.startsWith('m=')) videoSection = false;
    if (videoSection && /a=rtpmap:\d+ H264/i.test(line)) {
      const pt = line.match(/a=rtpmap:(\d+)/)[1];
      h264Pts.push(pt);
    }
  }

  if (h264Pts.length === 0) return sdp;

  return lines.map(line => {
    if (!line.startsWith('m=video')) return line;
    const parts = line.split(' ');
    const pts = parts.slice(3);
    const h264 = pts.filter(pt => h264Pts.includes(pt));
    const rest = pts.filter(pt => !h264Pts.includes(pt));
    return `${parts[0]} ${parts[1]} ${parts[2]} ${[...h264, ...rest].join(' ')}`;
  }).join('\r\n');
}

// Allow preferH264 to be required by Node.js tests (peer.js runs in the
// renderer where module is undefined, so this is a no-op in the browser).
if (typeof module !== 'undefined') module.exports = { preferH264 };

async function startWebRTC(stream) {
  const pc = new RTCPeerConnection({ iceServers: [] });

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

  const offer = await pc.createOffer();
  const mungedSdp = preferH264(offer.sdp);
  await pc.setLocalDescription({ type: offer.type, sdp: mungedSdp });
  window.sendSignaling({ type: offer.type, sdp: mungedSdp });

  return pc;
}
