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
    // Strip IPv4-mapped IPv6 prefix (::ffff:192.168.x.x → 192.168.x.x)
    const peer = req.socket.remoteAddress.replace(/^::ffff:/, '');
    onStatus(`Peer connected: ${peer} (${wss.clients.size} total)`, { clientIp: peer });

    // Notify existing peers that a new client joined so the renderer can
    // re-send stream-info. The signaling server does not buffer messages, so
    // a client that connects after stream-info was sent would never
    // receive it without this notification.
    wss.clients.forEach((client) => {
      if (client !== ws && client.readyState === WebSocket.OPEN) {
        client.send(JSON.stringify({ type: 'peer-joined' }));
      }
    });

    ws.on('message', (data, isBinary) => {
      wss.clients.forEach((client) => {
        if (client !== ws && client.readyState === WebSocket.OPEN) {
          client.send(data, { binary: isBinary });
        }
      });
    });

    ws.on('close', () => {
      onStatus(`Peer disconnected: ${peer} (${wss.clients.size} remaining)`);
      // Notify remaining peers so the host can stop the streamer
      wss.clients.forEach((client) => {
        if (client.readyState === WebSocket.OPEN) {
          client.send(JSON.stringify({ type: 'peer-left' }));
        }
      });
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
