const { test } = require('node:test');
const assert = require('node:assert/strict');
const { preferH264 } = require('../src/peer');

// Minimal SDP fixture with video section listing VP8, VP9, H264 (two PTs), H265.
// The m=video line order is VP8(96), VP9(97), H264(98), H264(99), H265(100).
function makeSdp(videoPtLine = 'UDP/TLS/RTP/SAVPF 96 97 98 99 100') {
  return [
    'v=0',
    'o=- 1 2 IN IP4 127.0.0.1',
    's=-',
    't=0 0',
    `m=video 9 ${videoPtLine}`,
    'c=IN IP4 0.0.0.0',
    'a=rtpmap:96 VP8/90000',
    'a=rtpmap:97 VP9/90000',
    'a=rtpmap:98 H264/90000',
    'a=rtpmap:99 H264/90000',
    'a=rtpmap:100 H265/90000',
    'm=audio 9 UDP/TLS/RTP/SAVPF 111',
    'a=rtpmap:111 opus/48000/2',
  ].join('\r\n');
}

test('moves H264 payload types to the front of the m=video line', () => {
  const result = preferH264(makeSdp());
  const mLine = result.split('\r\n').find(l => l.startsWith('m=video'));
  const pts = mLine.split(' ').slice(3);
  assert.deepEqual(pts.slice(0, 2), ['98', '99'], 'H264 PTs should be first');
  assert.deepEqual(pts.slice(2), ['96', '97', '100'], 'other PTs should follow');
});

test('preserves all other SDP lines unchanged', () => {
  const sdp = makeSdp();
  const result = preferH264(sdp);
  const inputLines = sdp.split('\r\n').filter(l => !l.startsWith('m=video'));
  const outputLines = result.split('\r\n').filter(l => !l.startsWith('m=video'));
  assert.deepEqual(outputLines, inputLines);
});

test('returns sdp unchanged when no H264 codec is present', () => {
  const sdp = [
    'v=0',
    'm=video 9 UDP/TLS/RTP/SAVPF 96 97',
    'a=rtpmap:96 VP8/90000',
    'a=rtpmap:97 VP9/90000',
  ].join('\r\n');
  assert.equal(preferH264(sdp), sdp);
});

test('handles SDP with no video section', () => {
  const sdp = [
    'v=0',
    'm=audio 9 UDP/TLS/RTP/SAVPF 111',
    'a=rtpmap:111 opus/48000/2',
  ].join('\r\n');
  assert.equal(preferH264(sdp), sdp);
});

test('is case-insensitive for H264 codec name', () => {
  const sdp = [
    'v=0',
    'm=video 9 UDP/TLS/RTP/SAVPF 96 97',
    'a=rtpmap:96 VP8/90000',
    'a=rtpmap:97 h264/90000',
  ].join('\r\n');
  const result = preferH264(sdp);
  const mLine = result.split('\r\n').find(l => l.startsWith('m=video'));
  assert.equal(mLine.split(' ')[3], '97', 'lowercase h264 should be recognised');
});

test('does not touch the audio m= line', () => {
  const result = preferH264(makeSdp());
  const audioLine = result.split('\r\n').find(l => l.startsWith('m=audio'));
  assert.equal(audioLine, 'm=audio 9 UDP/TLS/RTP/SAVPF 111');
});

test('works when H264 is already first', () => {
  const sdp = makeSdp('UDP/TLS/RTP/SAVPF 98 99 96 97 100');
  const result = preferH264(sdp);
  const mLine = result.split('\r\n').find(l => l.startsWith('m=video'));
  const pts = mLine.split(' ').slice(3);
  assert.deepEqual(pts.slice(0, 2), ['98', '99']);
});

// Fixture with fmtp profile-level-id lines: PT 98 = Constrained Baseline,
// PT 99 = High Profile. Used to test the baseline-pinning path.
function makeSdpWithProfiles() {
  return [
    'v=0',
    'o=- 1 2 IN IP4 127.0.0.1',
    's=-',
    't=0 0',
    'm=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99 100',
    'c=IN IP4 0.0.0.0',
    'a=rtpmap:96 VP8/90000',
    'a=rtpmap:97 VP9/90000',
    'a=rtpmap:98 H264/90000',
    'a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f',
    'a=rtcp-fb:98 nack',
    'a=rtpmap:99 H264/90000',
    'a=fmtp:99 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640032',
    'a=rtcp-fb:99 nack',
    'a=rtpmap:100 H265/90000',
    'm=audio 9 UDP/TLS/RTP/SAVPF 111',
    'a=rtpmap:111 opus/48000/2',
  ].join('\r\n');
}

test('pins to Constrained Baseline H264 when fmtp profile lines are present', () => {
  const result = preferH264(makeSdpWithProfiles());
  const mLine = result.split('\r\n').find(l => l.startsWith('m=video'));
  const pts = mLine.split(' ').slice(3);
  assert.equal(pts[0], '98', 'Constrained Baseline PT should be first');
  assert.ok(!pts.includes('99'), 'High Profile PT should be removed from m=video');
});

test('removes rtpmap, fmtp, and rtcp-fb lines for non-baseline H264 profiles', () => {
  const lines = preferH264(makeSdpWithProfiles()).split('\r\n');
  assert.ok(!lines.some(l => /profile-level-id=640032/i.test(l)), 'High Profile fmtp should be removed');
  assert.ok(!lines.some(l => /^a=rtpmap:99 /.test(l)), 'High Profile rtpmap should be removed');
  assert.ok(!lines.some(l => /^a=rtcp-fb:99 /.test(l)), 'High Profile rtcp-fb should be removed');
});

test('keeps Constrained Baseline fmtp and rtcp-fb lines', () => {
  const lines = preferH264(makeSdpWithProfiles()).split('\r\n');
  assert.ok(lines.some(l => /profile-level-id=42e01f/i.test(l)), 'Baseline fmtp should be kept');
  assert.ok(lines.some(l => /^a=rtcp-fb:98 /.test(l)), 'Baseline rtcp-fb should be kept');
});

test('falls back to all H264 variants when no Constrained Baseline fmtp present', () => {
  // makeSdp() has no fmtp lines so no profile can be identified
  const result = preferH264(makeSdp());
  const mLine = result.split('\r\n').find(l => l.startsWith('m=video'));
  const pts = mLine.split(' ').slice(3);
  assert.deepEqual(pts.slice(0, 2), ['98', '99'], 'all H264 PTs kept when no baseline fmtp');
});
