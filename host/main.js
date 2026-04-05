const { app, BrowserWindow, desktopCapturer, ipcMain, screen } = require('electron');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');
const fs = require('fs');
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
    height: 540,
    minWidth: 380,
    minHeight: 460,
    resizable: true,
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
  takeFireStickScreenshot(8000);
  return { ok: true, videoPort: VIDEO_PORT, inputPort: INPUT_PORT };
});

function takeFireStickScreenshot(delayMs = 3000) {
  setTimeout(() => {
    const dest = 'C:\\temp\\firestick_screen.png';
    const adb = spawn('adb', ['exec-out', 'screencap', '-p']);
    const chunks = [];
    adb.stdout.on('data', (chunk) => chunks.push(chunk));
    adb.stderr.on('data', (d) => console.warn('[screenshot] adb stderr:', d.toString().trim()));
    adb.on('close', (code) => {
      if (code === 0 && chunks.length > 0) {
        fs.writeFile(dest, Buffer.concat(chunks), (err) => {
          if (err) console.error('[screenshot] write failed:', err.message);
          else console.log('[screenshot] saved:', dest);
        });
      } else {
        console.warn('[screenshot] adb exited with code', code);
      }
    });
    adb.on('error', (err) => console.warn('[screenshot] adb error:', err.message));
  }, delayMs);
}

// Capture both screens simultaneously for latency measurement.
// Kicks off local desktopCapturer + adb screencap in parallel,
// saving timestamped PNGs to the system temp dir for side-by-side comparison.
ipcMain.handle('capture-latency', async () => {
  const tmpDir = app.getPath('temp');
  const timestamp = Date.now();
  const localDest = path.join(tmpDir, `latency_laptop_${timestamp}.png`);
  const remoteDest = path.join(tmpDir, `latency_firestick_${timestamp}.png`);

  // Start both captures concurrently
  const localPromise = desktopCapturer.getSources({
    types: ['screen'],
    thumbnailSize: { width: 1920, height: 1200 },
  }).then((sources) => {
    const src = sources[0];
    if (src) {
      fs.writeFileSync(localDest, src.thumbnail.toPNG());
      console.log('[latency] laptop screenshot saved:', localDest);
    }
  }).catch((err) => {
    console.warn('[latency] laptop capture failed:', err.message);
  });

  const adbPromise = new Promise((resolve) => {
    const adb = spawn('adb', ['exec-out', 'screencap', '-p']);
    const chunks = [];
    adb.stdout.on('data', (chunk) => chunks.push(chunk));
    adb.on('close', (code) => {
      if (code === 0 && chunks.length > 0) {
        fs.writeFileSync(remoteDest, Buffer.concat(chunks));
        console.log('[latency] firestick screenshot saved:', remoteDest);
        resolve({ ok: true });
      } else {
        resolve({ ok: false, error: `adb exited with code ${code}` });
      }
    });
    adb.on('error', (err) => resolve({ ok: false, error: err.message }));
  });

  const [, adbResult] = await Promise.all([localPromise, adbPromise]);

  return {
    laptop: localDest,
    firestick: adbResult.ok ? remoteDest : null,
    error: adbResult.ok ? null : adbResult.error,
  };
});

ipcMain.handle('stop-streamer', () => {
  stopStreamer();
  return { ok: true };
});

ipcMain.handle('get-local-ip', () => getLocalIP());
ipcMain.handle('get-signaling-port', () => SIGNALING_PORT);
ipcMain.handle('get-stream-ports', () => ({ videoPort: VIDEO_PORT, inputPort: INPUT_PORT }));
ipcMain.handle('restart', () => { app.relaunch(); app.exit(0); });

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
