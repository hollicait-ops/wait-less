// receiver.js — WebRTC answerer for the StreamBridge test receiver page
// Open localhost:3000 in Chrome while the Electron host is running.

const SIGNALING_URL = 'ws://localhost:8080';
const RETRY_INTERVAL_MS = 2000;

const videoEl = document.getElementById('video');
const overlay = document.getElementById('overlay');
const statusEl = document.getElementById('status');
const unmuteBtn = document.getElementById('unmute-btn');
const reconnectBtn = document.getElementById('reconnect-btn');

let ws = null;
let pc = null;
let retryTimer = null;

function setStatus(msg) {
  statusEl.textContent = msg;
  console.log('[status]', msg);
}

function showOverlay() {
  overlay.classList.remove('hidden');
}

function hideOverlay() {
  overlay.classList.add('hidden');
}

// --- Signaling ---

function connectSignaling() {
  if (retryTimer) { clearTimeout(retryTimer); retryTimer = null; }

  setStatus('Connecting to host...');
  ws = new WebSocket(SIGNALING_URL);

  ws.onopen = () => {
    setStatus('Connected to host — waiting for stream...');
    console.log('[signaling] connected');
  };

  ws.onmessage = async (event) => {
    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch (err) {
      console.warn('[signaling] unparseable message:', err.message);
      return;
    }
    await handleSignalingMessage(msg);
  };

  ws.onerror = (err) => {
    console.warn('[signaling] error:', err.message || err);
  };

  ws.onclose = () => {
    console.log('[signaling] closed — retrying in', RETRY_INTERVAL_MS, 'ms');
    ws = null;
    // Only retry automatically if we haven't yet received a stream
    if (!videoEl.srcObject) {
      setStatus('Waiting for host — retrying...');
      reconnectBtn.style.display = 'none';
      retryTimer = setTimeout(connectSignaling, RETRY_INTERVAL_MS);
    } else {
      setStatus('Stream ended');
      videoEl.srcObject = null;
      videoEl.style.display = 'none';
      showOverlay();
      reconnectBtn.style.display = '';
    }
  };
}

function sendSignaling(data) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(data));
  }
}

// --- WebRTC answerer ---

async function handleSignalingMessage(msg) {
  if (msg.type === 'offer') {
    await handleOffer(msg);
  } else if (msg.type === 'ice-candidate' && msg.candidate) {
    if (pc) {
      await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
    }
  }
}

async function handleOffer(offer) {
  // If a PC already exists (e.g. host restarted), tear it down first
  if (pc) {
    pc.close();
    pc = null;
    videoEl.srcObject = null;
    videoEl.style.display = 'none';
    showOverlay();
  }

  console.log('[sdp] offer received');
  logSdpCodecInfo(offer.sdp);

  pc = new RTCPeerConnection({ iceServers: [] });

  pc.onicecandidate = ({ candidate }) => {
    if (candidate) {
      sendSignaling({ type: 'ice-candidate', candidate });
    }
  };

  pc.oniceconnectionstatechange = () => {
    console.log('[ice] connection state:', pc.iceConnectionState);
  };

  pc.onconnectionstatechange = () => {
    const state = pc.connectionState;
    console.log('[peer] connection state:', state);
    if (state === 'disconnected' || state === 'failed') {
      setStatus('Stream ended');
      videoEl.srcObject = null;
      videoEl.style.display = 'none';
      showOverlay();
      reconnectBtn.style.display = '';
    }
  };

  pc.ontrack = (event) => {
    const { track } = event;
    console.log(`[track] ${track.kind} track received (id: ${track.id})`);

    // Log codec asynchronously via getStats
    setTimeout(() => {
      pc.getStats(track).then(stats => {
        stats.forEach(report => {
          if (report.type === 'codec') {
            console.log(`[codec] ${track.kind}: ${report.mimeType} (pt ${report.payloadType})`);
          }
        });
      });
    }, 1000);

    if (event.streams && event.streams[0]) {
      videoEl.srcObject = event.streams[0];
      videoEl.style.display = '';
      setStatus('Connected — receiving stream');
      unmuteBtn.style.display = '';
      // Keep overlay visible until user unmutes or dismisses
    }
  };

  await pc.setRemoteDescription(new RTCSessionDescription(offer));

  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);
  sendSignaling({ type: answer.type, sdp: answer.sdp });
  console.log('[sdp] answer sent');
}

// --- Codec/SDP diagnostics ---

function logSdpCodecInfo(sdp) {
  const lines = sdp.split(/\r?\n/);
  let inVideo = false;
  let inAudio = false;

  for (const line of lines) {
    if (line.startsWith('m=video')) { inVideo = true; inAudio = false; }
    else if (line.startsWith('m=audio')) { inAudio = true; inVideo = false; }
    else if (line.startsWith('m=')) { inVideo = false; inAudio = false; }

    if ((inVideo || inAudio) && line.startsWith('a=rtpmap:')) {
      const section = inVideo ? 'video' : 'audio';
      console.log(`[sdp] ${section} codec: ${line}`);
    }
    if (inAudio && line.startsWith('a=fmtp:') && line.includes('stereo')) {
      console.log('[sdp] audio fmtp (stereo):', line);
    }
  }
}

// --- Audio unmute ---

unmuteBtn.addEventListener('click', () => {
  videoEl.muted = false;
  unmuteBtn.style.display = 'none';
  hideOverlay();
});

// --- Boot ---

connectSignaling();
