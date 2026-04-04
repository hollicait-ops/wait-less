// Renderer process — signaling + stream control
// SB-21: replaced WebRTC peer/offer flow with FFmpeg+UDP streamer

let signalingWs = null;

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
    logStatus('Signaling connected — waiting for client...');
    streamStatus.textContent = 'Waiting for client...';
  };

  signalingWs.onmessage = async (event) => {
    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch (err) {
      logStatus(`Signaling: unparseable message — ${err.message}`);
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
  if (msg.type === 'peer-joined') {
    logStatus('Client joined — starting streamer and sending stream-info...');
    await startStreamingToClient();
    return;
  }

  if (msg.type === 'client-ready') {
    logStatus('Client ready — stream active');
    streamStatus.textContent = 'Streaming';
  }
}

async function startStreamingToClient() {
  const result = await streambridge.startStreamer();
  if (!result.ok) {
    logStatus(`Failed to start streamer: ${result.error}`);
    return;
  }
  // Tell the client which ports to use
  sendSignaling({ type: 'stream-info', videoPort: result.videoPort, inputPort: result.inputPort });
  streamStatus.textContent = 'Streaming';
  logStatus(`Streaming via UDP — video:${result.videoPort} input:${result.inputPort}`);
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
  logStatus('Waiting for Fire Stick to connect...');
});

init();
