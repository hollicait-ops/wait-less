const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const HOST_DIR = path.join(__dirname, '..');
const RENDERER_SCRIPTS = [
  path.join(HOST_DIR, 'renderer', 'renderer.js'),
];

// Names that npm places on PATH and that cmd.exe would resolve before
// node_modules/.bin/ if a same-named .js file exists in the working directory
// (Windows PATHEXT includes .JS and searches the current directory first).
const SHADOWED_EXECUTABLES = ['electron', 'node', 'npm', 'npx'];

test('no file in host/ shadows a PATH executable on Windows', () => {
  for (const name of SHADOWED_EXECUTABLES) {
    const conflict = path.join(HOST_DIR, `${name}.js`);
    assert.equal(
      fs.existsSync(conflict),
      false,
      `${name}.js exists in host/ — on Windows, cmd.exe resolves this before ` +
      `node_modules/.bin/${name}.cmd, so \`npm run start\` would open the file ` +
      `instead of launching ${name}. Rename the file.`,
    );
  }
});

test('CSP connect-src port matches SIGNALING_PORT in main.js', () => {
  const mainSrc = fs.readFileSync(path.join(HOST_DIR, 'main.js'), 'utf8');
  const portMatch = mainSrc.match(/\bSIGNALING_PORT\s*=\s*(\d+)/);
  assert.ok(portMatch, 'Could not find SIGNALING_PORT constant in main.js');
  const port = portMatch[1];

  const htmlSrc = fs.readFileSync(
    path.join(HOST_DIR, 'renderer', 'index.html'),
    'utf8',
  );
  const cspMatch = htmlSrc.match(/http-equiv="Content-Security-Policy"[^>]*content="([^"]*)"/);
  assert.ok(cspMatch, 'Could not find Content-Security-Policy meta tag in index.html');
  const csp = cspMatch[1];

  assert.ok(
    csp.includes(`ws://localhost:${port}`),
    `CSP connect-src must include ws://localhost:${port} to match SIGNALING_PORT. ` +
    `If you changed the port in main.js, update the connect-src in renderer/index.html too.`,
  );
});

test('renderer scripts do not redeclare names exposed by contextBridge', () => {
  const preloadSrc = fs.readFileSync(
    path.join(HOST_DIR, 'renderer', 'preload.js'),
    'utf8',
  );

  // Extract every name passed to exposeInMainWorld, e.g. exposeInMainWorld('waitless', ...)
  const exposed = [...preloadSrc.matchAll(/exposeInMainWorld\(\s*['"](\w+)['"]/g)]
    .map(m => m[1]);

  assert.ok(
    exposed.length > 0,
    'Could not find any exposeInMainWorld calls in preload.js — check the regex',
  );

  for (const file of RENDERER_SCRIPTS) {
    const src = fs.readFileSync(file, 'utf8');
    const rel = path.relative(HOST_DIR, file);

    for (const name of exposed) {
      // Matches: const { name } = window  /  const { name, other } = window
      // Also matches: const name = window.name  (alternate form)
      const destructure = new RegExp(
        `\\bconst\\s*\\{[^}]*\\b${name}\\b[^}]*\\}\\s*=\\s*window\\b`,
      );
      const direct = new RegExp(
        `\\bconst\\s+${name}\\s*=\\s*window\\.${name}\\b`,
      );

      assert.equal(
        destructure.test(src) || direct.test(src),
        false,
        `${rel} redeclares '${name}', which contextBridge already exposes as a ` +
        `non-configurable global. This causes a SyntaxError in the renderer ` +
        `before any code runs. Remove the declaration and use ${name} directly.`,
      );
    }
  }
});
