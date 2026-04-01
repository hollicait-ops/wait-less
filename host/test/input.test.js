const { test } = require('node:test');
const assert = require('node:assert/strict');
const { replayInputEvent } = require('../src/input');

const SCREEN = { width: 1920, height: 1080 };

function mockRobot() {
  const calls = [];
  return {
    calls,
    moveMouse:   (...args) => calls.push(['moveMouse',   ...args]),
    mouseClick:  (...args) => calls.push(['mouseClick',  ...args]),
    scrollMouse: (...args) => calls.push(['scrollMouse', ...args]),
    keyToggle:   (...args) => calls.push(['keyToggle',   ...args]),
  };
}

// --- mousemove ---

test('mousemove maps normalised coordinates to pixels', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'mousemove', x: 0.5, y: 0.25 });
  assert.deepEqual(robot.calls, [['moveMouse', 960, 270]]);
});

test('mousemove at boundary 0,0 maps to top-left pixel', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'mousemove', x: 0, y: 0 });
  assert.deepEqual(robot.calls, [['moveMouse', 0, 0]]);
});

test('mousemove at boundary 1,1 maps to bottom-right pixel', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'mousemove', x: 1, y: 1 });
  assert.deepEqual(robot.calls, [['moveMouse', 1920, 1080]]);
});

test('mousemove with x out of range is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'mousemove', x: 1.1, y: 0.5 });
  assert.equal(robot.calls.length, 0);
});

test('mousemove with y out of range is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'mousemove', x: 0.5, y: -0.1 });
  assert.equal(robot.calls.length, 0);
});

test('mousemove with non-number coordinates is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'mousemove', x: '0.5', y: 0.5 });
  assert.equal(robot.calls.length, 0);
});

test('mousemove with NaN coordinates is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'mousemove', x: NaN, y: 0.5 });
  assert.equal(robot.calls.length, 0);
});

// --- click ---

test('click left calls mouseClick with left', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'click', button: 'left' });
  assert.deepEqual(robot.calls, [['mouseClick', 'left']]);
});

test('click right calls mouseClick with right', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'click', button: 'right' });
  assert.deepEqual(robot.calls, [['mouseClick', 'right']]);
});

test('click middle calls mouseClick with middle', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'click', button: 'middle' });
  assert.deepEqual(robot.calls, [['mouseClick', 'middle']]);
});

test('click with disallowed button is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'click', button: 'inject;rm -rf /' });
  assert.equal(robot.calls.length, 0);
});

// --- scroll ---

test('scroll calls scrollMouse with x=0 and provided dy', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'scroll', dy: -3 });
  assert.deepEqual(robot.calls, [['scrollMouse', 0, -3]]);
});

test('scroll with non-number dy is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'scroll', dy: 'fast' });
  assert.equal(robot.calls.length, 0);
});

test('scroll with NaN dy is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'scroll', dy: NaN });
  assert.equal(robot.calls.length, 0);
});

test('scroll with float dy is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'scroll', dy: 1.5 });
  assert.equal(robot.calls.length, 0);
});

// --- keydown / keyup ---

test('keydown calls keyToggle with down', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'keydown', key: 'Return' });
  assert.deepEqual(robot.calls, [['keyToggle', 'Return', 'down']]);
});

test('keyup calls keyToggle with up', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'keyup', key: 'Return' });
  assert.deepEqual(robot.calls, [['keyToggle', 'Return', 'up']]);
});

test('key with empty string is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'keydown', key: '' });
  assert.equal(robot.calls.length, 0);
});

test('key longer than 32 characters is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'keydown', key: 'a'.repeat(33) });
  assert.equal(robot.calls.length, 0);
});

// --- unknown type ---

test('unknown event type is ignored', () => {
  const robot = mockRobot();
  replayInputEvent(robot, SCREEN, { type: 'hack', payload: 'anything' });
  assert.equal(robot.calls.length, 0);
});
