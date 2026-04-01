const { app, BrowserWindow, ipcMain, screen, desktopCapturer } = require('electron');
const os = require('os');
const path = require('path');
const { createSignalingServer } = require('./src/signaling');
const { replayInputEvent } = require('./src/input');

const SIGNALING_PORT = 8080;

let mainWindow = null;
let signalingServer = null;
let robot = null;

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
    height: 320,
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
  signalingServer = createSignalingServer(SIGNALING_PORT, (msg) => {
    console.log('[signaling]', msg);
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

ipcMain.on('input-event', (_event, data) => {
  if (!robot) return;
  replayInputEvent(robot, screen.getPrimaryDisplay().size, data);
});

ipcMain.handle('get-local-ip', () => getLocalIP());
ipcMain.handle('get-signaling-port', () => SIGNALING_PORT);
ipcMain.handle('get-desktop-sources', async () => {
  const sources = await desktopCapturer.getSources({ types: ['screen'] });
  return sources.map(({ id, name }) => ({ id, name }));
});

app.whenReady().then(() => {
  loadRobotjs();
  startSignalingServer();
  createWindow();
});

app.on('before-quit', () => {
  if (signalingServer) signalingServer.close();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
  if (mainWindow === null) createWindow();
});
