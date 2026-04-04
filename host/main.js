const { app, BrowserWindow, ipcMain, screen } = require('electron');
const os = require('os');
const path = require('path');
const { createSignalingServer } = require('./src/signaling');
const { replayInputEvent } = require('./src/input');
const { startStreamer, stopStreamer, VIDEO_PORT, INPUT_PORT } = require('./src/streamer');

const SIGNALING_PORT = 8080;

let mainWindow = null;
let signalingServer = null;
let robot = null;

// IP of the most recently connected signaling client — used as the UDP stream target
let lastClientIp = null;

function getLocalIP() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return '127.0.0.1';
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 480,
    height: 500,
    resizable: false,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'renderer', 'preload.js'),
    },
    title: 'StreamBridge',
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  mainWindow.on('closed', () => { mainWindow = null; });
}

function startSignalingServer() {
  signalingServer = createSignalingServer(SIGNALING_PORT, (msg, meta) => {
    console.log('[signaling]', msg);
    if (meta && meta.clientIp) {
      lastClientIp = meta.clientIp;
      console.log('[main] client IP recorded:', lastClientIp);
    }
    if (mainWindow) {
      mainWindow.webContents.send('signaling-status', msg);
    }
  });
}

function loadRobotjs() {
  try {
    robot = require('robotjs');
  } catch {
    console.warn('[input] robotjs not available — input relay disabled');
  }
}

// ---- IPC handlers ----

// Renderer asks main to start the FFmpeg streamer targeting the last-seen client IP
ipcMain.handle('start-streamer', () => {
  if (!lastClientIp) {
    console.warn('[main] start-streamer called but no client IP known yet');
    return { ok: false, error: 'No client connected' };
  }
  startStreamer({
    clientIp: lastClientIp,
    videoPort: VIDEO_PORT,
    inputPort: INPUT_PORT,
    onInput: (event) => {
      if (!robot) return;
      replayInputEvent(robot, screen.getPrimaryDisplay().size, event);
    },
    onLog: (msg) => {
      console.log(msg);
      if (mainWindow) mainWindow.webContents.send('signaling-status', msg);
    },
  });
  return { ok: true, videoPort: VIDEO_PORT, inputPort: INPUT_PORT };
});

ipcMain.handle('stop-streamer', () => {
  stopStreamer();
  return { ok: true };
});

ipcMain.handle('get-local-ip', () => getLocalIP());
ipcMain.handle('get-signaling-port', () => SIGNALING_PORT);
ipcMain.handle('get-stream-ports', () => ({ videoPort: VIDEO_PORT, inputPort: INPUT_PORT }));

app.whenReady().then(() => {
  loadRobotjs();
  startSignalingServer();
  createWindow();
});

app.on('before-quit', () => {
  stopStreamer();
  if (signalingServer) signalingServer.close();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
  if (mainWindow === null) createWindow();
});
