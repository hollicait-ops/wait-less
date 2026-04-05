// Renderer process — signaling + stream control
// SB-21: replaced WebRTC peer/offer flow with FFmpeg+UDP streamer

let signalingWs = null;

// --- UI refs ---
const ipDisplay = document.getElementById('ip-display');
const portDisplay = document.getElementById('port-display');
const startBtn = document.getElementById('start-btn');
const restartBtn = document.getElementById('restart-btn');
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
  if (msg.type === 'peer-left') {
    logStatus('Client disconnected — stopping streamer');
    streamStatus.textContent = 'Waiting for client...';
    await streambridge.stopStreamer();
    return;
  }

  if (msg.type === 'peer-joined') {
    // Send stream-info so the client can bind its UDP socket,
    // but don't start FFmpeg yet — wait for client-ready.
    const ports = await streambridge.getStreamPorts();
    sendSignaling({ type: 'stream-info', videoPort: ports.videoPort, inputPort: ports.inputPort });
    logStatus('Client joined — sent stream-info, waiting for client-ready...');
    streamStatus.textContent = 'Waiting for client receiver...';
    return;
  }

  if (msg.type === 'client-ready') {
    logStatus('Client ready — starting streamer...');
    const result = await streambridge.startStreamer();
    if (!result.ok) {
      logStatus(`Failed to start streamer: ${result.error}`);
      return;
    }
    streamStatus.textContent = 'Streaming';
    logStatus(`Streaming via UDP — video:${result.videoPort} input:${result.inputPort}`);
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
  logStatus('Waiting for Fire Stick to connect...');
});

restartBtn.addEventListener('click', () => streambridge.restart());

// --- Debug tools (hidden unless STREAMBRIDGE_DEBUG=1) ---
async function initDebugTools() {
  const debug = await streambridge.isDebug();
  if (!debug) return;

  const latencyClock = document.getElementById('latency-clock');
  const latencyToggle = document.getElementById('latency-toggle');
  const captureLatencyBtn = document.getElementById('capture-latency-btn');

  latencyToggle.style.display = '';
  captureLatencyBtn.style.display = '';

  let clockRunning = false;
  let clockRaf = null;

  function tickClock() {
    const t = performance.now();
    const secs = Math.floor(t / 1000) % 10000;
    const ms = Math.floor(t % 1000);
    latencyClock.textContent = `${String(secs).padStart(4, '0')}.${String(ms).padStart(3, '0')}`;
    clockRaf = requestAnimationFrame(tickClock);
  }

  latencyToggle.addEventListener('click', () => {
    clockRunning = !clockRunning;
    if (clockRunning) {
      latencyClock.style.display = 'block';
      latencyToggle.textContent = 'Hide clock';
      tickClock();
    } else {
      latencyClock.style.display = 'none';
      latencyToggle.textContent = 'Latency clock';
      cancelAnimationFrame(clockRaf);
      clockRaf = null;
    }
  });

  captureLatencyBtn.addEventListener('click', async () => {
    captureLatencyBtn.disabled = true;
    captureLatencyBtn.textContent = 'Capturing...';
    try {
      const result = await streambridge.captureLatency();
      if (result.error) {
        logStatus(`Latency capture failed: ${result.error}`);
      } else {
        logStatus(`Latency screenshots saved -- host: ${result.host}, firestick: ${result.firestick}`);
      }
    } catch (err) {
      logStatus(`Latency capture error: ${err.message}`);
    }
    captureLatencyBtn.disabled = false;
    captureLatencyBtn.textContent = 'Capture latency';
  });
}

init();
initDebugTools();
