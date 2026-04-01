const { test, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const { createSignalingServer } = require('../src/signaling');

// Connects a WS client and waits for the open event.
function connect(port) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://localhost:${port}`);
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}

// Resolves with the next message received on ws.
function nextMessage(ws) {
  return new Promise((resolve, reject) => {
    ws.once('message', (data) => resolve(data.toString()));
    ws.once('error', reject);
  });
}

// Closes a WS client and waits for the close event.
function close(ws) {
  return new Promise((resolve) => {
    if (ws.readyState === WebSocket.CLOSED) return resolve();
    ws.on('close', resolve);
    ws.close();
  });
}

// Closes the server and waits for it to finish.
function closeServer(wss) {
  return new Promise((resolve, reject) => wss.close(err => err ? reject(err) : resolve()));
}

let wss;
let port;

beforeEach(() => {
  return new Promise((resolve) => {
    wss = createSignalingServer(0, () => {});
    wss.on('listening', () => {
      port = wss.address().port;
      resolve();
    });
  });
});

afterEach(async () => {
  await closeServer(wss);
});

test('relays a message from one client to another', async () => {
  const [a, b] = await Promise.all([connect(port), connect(port)]);
  const received = nextMessage(b);
  a.send('{"type":"offer","sdp":"test"}');
  assert.equal(await received, '{"type":"offer","sdp":"test"}');
  await Promise.all([close(a), close(b)]);
});

test('does not echo the message back to the sender', async () => {
  const [a, b] = await Promise.all([connect(port), connect(port)]);

  let senderGotMessage = false;
  a.on('message', () => { senderGotMessage = true; });

  const received = nextMessage(b);
  a.send('{"type":"ice-candidate","candidate":{}}');
  await received;

  // Give a tick for any spurious echo to arrive
  await new Promise(r => setTimeout(r, 20));
  assert.equal(senderGotMessage, false);
  await Promise.all([close(a), close(b)]);
});

test('relays to all other connected clients', async () => {
  const [a, b, c] = await Promise.all([connect(port), connect(port), connect(port)]);
  const [rb, rc] = [nextMessage(b), nextMessage(c)];
  a.send('hello');
  assert.equal(await rb, 'hello');
  assert.equal(await rc, 'hello');
  await Promise.all([close(a), close(b), close(c)]);
});

test('continues relaying after one client disconnects', async () => {
  const [a, b, c] = await Promise.all([connect(port), connect(port), connect(port)]);

  // Disconnect b; a → c should still work
  await close(b);
  // Small delay to let the server process the close event
  await new Promise(r => setTimeout(r, 20));

  const received = nextMessage(c);
  a.send('{"type":"offer","sdp":"after-disconnect"}');
  assert.equal(await received, '{"type":"offer","sdp":"after-disconnect"}');
  await Promise.all([close(a), close(c)]);
});

test('handles a re-offer after the first session completes', async () => {
  const [a, b] = await Promise.all([connect(port), connect(port)]);

  // First offer/answer exchange
  const firstMsg = nextMessage(b);
  a.send('{"type":"offer","sdp":"first"}');
  assert.equal(await firstMsg, '{"type":"offer","sdp":"first"}');

  // Second offer (host restarted)
  const secondMsg = nextMessage(b);
  a.send('{"type":"offer","sdp":"second"}');
  assert.equal(await secondMsg, '{"type":"offer","sdp":"second"}');

  await Promise.all([close(a), close(b)]);
});

test('single connected client does not error when sending', async () => {
  const a = await connect(port);
  // No other client — message goes nowhere, but should not throw
  assert.doesNotThrow(() => a.send('{"type":"offer"}'));
  await close(a);
});
