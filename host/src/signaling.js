const { WebSocketServer, WebSocket } = require('ws');

/**
 * Creates a minimal two-peer signaling relay server.
 * Relays every message from one connected client to all others.
 * No room management — this is a LAN-only, single-session app.
 *
 * @param {number} port
 * @param {function} onStatus  called with a status string on key events
 * @returns {WebSocketServer}
 */
function createSignalingServer(port, onStatus) {
  const wss = new WebSocketServer({ port });

  wss.on('listening', () => {
    onStatus(`Signaling server listening on port ${port}`);
  });

  wss.on('connection', (ws, req) => {
    const peer = req.socket.remoteAddress;
    onStatus(`Peer connected: ${peer} (${wss.clients.size} total)`);

    ws.on('message', (data) => {
      wss.clients.forEach((client) => {
        if (client !== ws && client.readyState === WebSocket.OPEN) {
          client.send(data);
        }
      });
    });

    ws.on('close', () => {
      onStatus(`Peer disconnected: ${peer} (${wss.clients.size} remaining)`);
    });

    ws.on('error', (err) => {
      onStatus(`WebSocket error from ${peer}: ${err.message}`);
    });
  });

  wss.on('error', (err) => {
    onStatus(`Signaling server error: ${err.message}`);
  });

  return wss;
}

module.exports = { createSignalingServer };
