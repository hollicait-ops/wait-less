const { test } = require('node:test');
const assert = require('node:assert/strict');
const { preferH264, stripColorSpaceExtmap } = require('../src/peer');

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

test('puts Constrained Baseline H264 first when fmtp profile lines are present', () => {
  const result = preferH264(makeSdpWithProfiles());
  const mLine = result.split('\r\n').find(l => l.startsWith('m=video'));
  const pts = mLine.split(' ').slice(3);
  assert.equal(pts[0], '98', 'Constrained Baseline PT should be first');
  assert.ok(pts.includes('99'), 'High Profile PT should still be present');
});

test('preserves all rtpmap, fmtp, and rtcp-fb lines — no PTs removed', () => {
  const lines = preferH264(makeSdpWithProfiles()).split('\r\n');
  assert.ok(lines.some(l => /profile-level-id=640032/i.test(l)), 'High Profile fmtp should be kept');
  assert.ok(lines.some(l => /^a=rtpmap:99 /.test(l)), 'High Profile rtpmap should be kept');
  assert.ok(lines.some(l => /^a=rtcp-fb:99 /.test(l)), 'High Profile rtcp-fb should be kept');
  assert.ok(lines.some(l => /profile-level-id=42e01f/i.test(l)), 'Baseline fmtp should be kept');
  assert.ok(lines.some(l => /^a=rtcp-fb:98 /.test(l)), 'Baseline rtcp-fb should be kept');
});

test('High Profile PT follows Constrained Baseline in m=video line', () => {
  const result = preferH264(makeSdpWithProfiles());
  const mLine = result.split('\r\n').find(l => l.startsWith('m=video'));
  const pts = mLine.split(' ').slice(3);
  const idx98 = pts.indexOf('98');
  const idx99 = pts.indexOf('99');
  assert.ok(idx98 < idx99, 'Constrained Baseline (98) should precede High Profile (99)');
});

test('falls back to reordering all H264 variants when no Constrained Baseline fmtp present', () => {
  const result = preferH264(makeSdp());
  const mLine = result.split('\r\n').find(l => l.startsWith('m=video'));
  const pts = mLine.split(' ').slice(3);
  assert.deepEqual(pts.slice(0, 2), ['98', '99'], 'all H264 PTs kept and reordered to front');
});

// --- stripColorSpaceExtmap ---

function makeSdpWithColorSpaceExt() {
  return [
    'v=0',
    'o=- 1 2 IN IP4 127.0.0.1',
    's=-',
    't=0 0',
    'm=video 9 UDP/TLS/RTP/SAVPF 96',
    'a=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time',
    'a=extmap:8 http://www.webrtc.org/experiments/rtp-hdrext/color-space',
    'a=extmap:13 urn:3gpp:video-orientation',
    'a=rtpmap:96 H264/90000',
    'm=audio 9 UDP/TLS/RTP/SAVPF 111',
    'a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level',
    'a=rtpmap:111 opus/48000/2',
  ].join('\r\n');
}

test('removes the color-space extmap line from the video section', () => {
  const result = stripColorSpaceExtmap(makeSdpWithColorSpaceExt());
  assert.ok(!result.includes('color-space'), 'color-space extmap should be removed');
});

test('preserves other extmap lines in the video section', () => {
  const result = stripColorSpaceExtmap(makeSdpWithColorSpaceExt());
  assert.ok(result.includes('abs-send-time'), 'abs-send-time extmap should be kept');
  assert.ok(result.includes('video-orientation'), 'video-orientation extmap should be kept');
});

test('does not touch audio section extmap lines', () => {
  const result = stripColorSpaceExtmap(makeSdpWithColorSpaceExt());
  assert.ok(result.includes('ssrc-audio-level'), 'audio extmap should be untouched');
});

test('returns sdp unchanged when no color-space extmap is present', () => {
  const sdp = makeSdp();
  assert.equal(stripColorSpaceExtmap(sdp), sdp);
});
