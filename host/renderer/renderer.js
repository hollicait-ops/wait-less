// Renderer process — WebRTC offerer
// SB-3: Signaling WS connection + UI wiring
// SB-4: Screen/audio capture + WebRTC peer connection

const { streambridge } = window;

let signalingWs = null;
let peerConnection = null;

// --- UI refs ---
const ipDisplay = document.getElementById('ip-display');
const portDisplay = document.getElementById('port-display');
const startBtn = document.getElementById('start-btn');
const streamStatus = document.getElementById('stream-status');
const statusLog = document.getElementById('status-log');

function logStatus(msg) {
  const line = document.createElement('div');
  line.className = 'status-line';
  line.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
  statusLog.appendChild(line);
  statusLog.scrollTop = statusLog.scrollHeight;
}

// --- Init: display local IP ---
async function init() {
  const ip = await streambridge.getLocalIP();
  const port = await streambridge.getSignalingPort();
  ipDisplay.textContent = ip;
  portDisplay.textContent = port;

  streambridge.onSignalingStatus((msg) => logStatus(msg));
}

// --- Signaling WebSocket ---
function connectSignaling(port) {
  const url = `ws://localhost:${port}`;
  signalingWs = new WebSocket(url);

  signalingWs.onopen = () => {
    logStatus('Renderer connected to signaling server');
    streamStatus.textContent = 'Signaling connected — waiting for client...';
  };

  signalingWs.onmessage = async (event) => {
    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch (err) {
      logStatus(`Signaling: received unparseable message — ${err.message}`);
      return;
    }
    await handleSignalingMessage(msg);
  };

  signalingWs.onerror = (err) => {
    logStatus(`Signaling error: ${err.message || 'unknown'}`);
    startBtn.disabled = false;
  };

  signalingWs.onclose = () => {
    logStatus('Signaling disconnected');
    streamStatus.textContent = 'Disconnected — restart to reconnect';
    startBtn.disabled = false;
  };
}

async function handleSignalingMessage(msg) {
  if (!peerConnection) return;

  if (msg.type === 'answer') {
    const desc = new RTCSessionDescription(msg);
    await peerConnection.setRemoteDescription(desc);
    logStatus('Remote SDP answer applied');
    streamStatus.textContent = 'Streaming';
  } else if (msg.type === 'ice-candidate' && msg.candidate) {
    await peerConnection.addIceCandidate(new RTCIceCandidate(msg.candidate));
  }
}

function sendSignaling(data) {
  if (signalingWs && signalingWs.readyState === WebSocket.OPEN) {
    signalingWs.send(JSON.stringify(data));
  }
}

// --- Start button ---
startBtn.addEventListener('click', async () => {
  startBtn.disabled = true;
  const port = await streambridge.getSignalingPort();
  connectSignaling(port);

  let stream;
  try {
    logStatus('Starting screen capture...');
    stream = await startCapture();
    logStatus(`Capture started — ${stream.getTracks().length} track(s)`);
    await startWebRTC(stream);
    logStatus('Offer sent — waiting for client to answer');
    streamStatus.textContent = 'Waiting for client...';
  } catch (err) {
    if (stream) stream.getTracks().forEach(t => t.stop());
    logStatus(`Failed to start: ${err.message}`);
    if (signalingWs) {
      signalingWs.onclose = null;
      signalingWs.close();
      signalingWs = null;
    }
    streamStatus.textContent = 'Waiting for connection...';
    startBtn.disabled = false;
  }
});

// Hooks used by peer.js — set before any signaling messages arrive from the
// remote peer, otherwise incoming SDP answers and ICE candidates are silently
// dropped (handleSignalingMessage guards on peerConnection being non-null).
window.sendSignaling = sendSignaling;
window.setPeerConnection = (pc) => { peerConnection = pc; };
window.updateStreamStatus = (msg) => { streamStatus.textContent = msg; };

init();
