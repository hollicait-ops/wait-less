// input.js — validate and replay input events via robotjs
// Extracted as a pure function so it can be unit-tested without Electron.

const ALLOWED_BUTTONS = new Set(['left', 'right', 'middle']);

/**
 * Replay a single input event using the provided robot instance.
 *
 * @param {object} robot     - robotjs instance (or a test double)
 * @param {{width: number, height: number}} screenSize - primary display dimensions
 * @param {object} data      - parsed DataChannel message
 */
function replayInputEvent(robot, screenSize, data) {
  const { type, x, y, button, key, dy } = data;
  const { width, height } = screenSize;

  if (type === 'mousemove') {
    if (typeof x !== 'number' || typeof y !== 'number' || x < 0 || x > 1 || y < 0 || y > 1) return;
    robot.moveMouse(Math.round(x * width), Math.round(y * height));
  } else if (type === 'click') {
    if (!ALLOWED_BUTTONS.has(button)) return;
    robot.mouseClick(button);
  } else if (type === 'scroll') {
    if (typeof dy !== 'number') return;
    robot.scrollMouse(0, dy);
  } else if (type === 'keydown') {
    if (typeof key !== 'string' || key.length === 0 || key.length > 32) return;
    robot.keyToggle(key, 'down');
  } else if (type === 'keyup') {
    if (typeof key !== 'string' || key.length === 0 || key.length > 32) return;
    robot.keyToggle(key, 'up');
  }
}

module.exports = { replayInputEvent };
