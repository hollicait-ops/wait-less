const { app, BrowserWindow, ipcMain, screen } = require('electron');
const os = require('os');
const path = require('path');
const { createSignalingServer } = require('./src/signaling');

const SIGNALING_PORT = 8080;

let mainWindow = null;
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
  createSignalingServer(SIGNALING_PORT, (msg) => {
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
  const { type, x, y, button, key, dy } = data;
  const { width, height } = screen.getPrimaryDisplay().size;

  if (type === 'mousemove') {
    robot.moveMouse(Math.round(x * width), Math.round(y * height));
  } else if (type === 'click') {
    robot.mouseClick(button);
  } else if (type === 'scroll') {
    robot.scrollMouse(0, dy);
  } else if (type === 'keydown') {
    robot.keyToggle(key, 'down');
  } else if (type === 'keyup') {
    robot.keyToggle(key, 'up');
  }
});

ipcMain.handle('get-local-ip', () => getLocalIP());
ipcMain.handle('get-signaling-port', () => SIGNALING_PORT);

app.whenReady().then(() => {
  loadRobotjs();
  startSignalingServer();
  createWindow();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
  if (mainWindow === null) createWindow();
});
