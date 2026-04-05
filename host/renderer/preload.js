const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('streambridge', {
  getLocalIP: () => ipcRenderer.invoke('get-local-ip'),
  getSignalingPort: () => ipcRenderer.invoke('get-signaling-port'),
  onSignalingStatus: (cb) => {
    ipcRenderer.removeAllListeners('signaling-status');
    ipcRenderer.on('signaling-status', (_e, msg) => cb(msg));
  },
  sendInputEvent: (data) => ipcRenderer.send('input-event', data),
  getStreamPorts: () => ipcRenderer.invoke('get-stream-ports'),
  startStreamer: () => ipcRenderer.invoke('start-streamer'),
  stopStreamer: () => ipcRenderer.invoke('stop-streamer'),
  restart: () => ipcRenderer.invoke('restart'),
  captureLatency: () => ipcRenderer.invoke('capture-latency'),
});
