const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('streambridge', {
  getLocalIP: () => ipcRenderer.invoke('get-local-ip'),
  getSignalingPort: () => ipcRenderer.invoke('get-signaling-port'),
  onSignalingStatus: (cb) => ipcRenderer.on('signaling-status', (_e, msg) => cb(msg)),
  sendInputEvent: (data) => ipcRenderer.send('input-event', data),
});
