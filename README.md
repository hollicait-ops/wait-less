# StreamBridge

Stream your Windows desktop to an Amazon Fire Stick over local WiFi. No cloud services, no relay servers, no new hardware. Measured latency is ~120-150ms glass-to-glass.

Architecturally similar to Steam Link: an Electron host captures the display and streams it via FFmpeg + raw UDP, a native Android app on the Fire Stick decodes and renders it, and the Fire Stick D-pad acts as a mouse/keyboard.

---

## Requirements

### Host (Windows PC)

- Windows 10 or 11
- [Node.js 20+](https://nodejs.org/en/download)
- Electron 28+ (installed automatically via `npm install`)
- **FFmpeg** on your PATH — used for screen capture and H.264 encoding

  ```powershell
  winget install Gyan.FFmpeg
  ```

  Restart your terminal afterwards and verify with `ffmpeg -version`.

- `adb` (Android Debug Bridge) on your PATH — used to deploy the app to the Fire Stick over WiFi

  The easiest way to get `adb` is via [Android Studio](https://developer.android.com/studio), which installs it at `%LOCALAPPDATA%\Android\Sdk\platform-tools\`. If you don't want the full IDE, download the [standalone platform-tools zip](https://developer.android.com/tools/releases/platform-tools), extract it (e.g. `C:\platform-tools\`), and add that folder to your PATH:

  ```powershell
  [Environment]::SetEnvironmentVariable("Path", $env:Path + ";C:\platform-tools", "User")
  ```

  Restart your terminal afterwards and verify with `adb version`.

### Client (Amazon Fire Stick)

- Fire Stick Gen 2 or later (Fire OS 5+, Android API 22+)
- ADB debugging enabled
- Apps from unknown sources enabled

### Network

- Both devices on the **same 5GHz WiFi network** — 2.4GHz will likely add significant latency
- No port forwarding or internet access required

---

## Quick Start

### 1. Start the host

```bash
cd host
npm install
npm run start
```

The app window displays the host's local IP address and a **Start Streaming** button. Note the IP — you'll enter it on the Fire Stick.

### 2. Set up the Fire Stick

**Physical setup:**

1. Plug the Fire Stick into an HDMI port on your TV.
2. Plug the Fire Stick's power adapter into a wall outlet (not the TV's USB port — it may not supply enough power).
3. Turn on the TV and switch to the correct HDMI input.

**Initial Fire OS setup** (skip if already done):

4. Follow the on-screen prompts to select your language and sign in to your Amazon account.
5. When prompted for WiFi, connect to the **same 5GHz network** your Windows PC is on. Using 2.4GHz will likely add significant latency.

**Enable developer options:**

6. Go to **Settings** (gear icon in the top menu bar) → **My Fire TV** → **About**.
7. Scroll down to **Fire TV Stick** and click it **7 times** in a row. You will see a countdown, then a message saying "You are now a developer".
8. Press the Back button to return to **My Fire TV**. A new **Developer Options** item will now appear in the list.
9. Open **Developer Options** and turn on **ADB Debugging**.
10. In the same screen, turn on **Apps from Unknown Sources**.

**Find the Fire Stick's IP address:**

11. Go to **Settings** → **My Fire TV** → **About** → **Network**. Note the IP address shown (e.g. `192.168.1.42`). You will need this in the next step.

**Install the app:**

```bash
# Connect to the Fire Stick over WiFi ADB (use the IP from step 11)
adb connect <firestick-ip>:5555

# Build and install
cd client
./gradlew installDebug
```

If `adb connect` times out, make sure your PC and Fire Stick are on the same WiFi network and that ADB Debugging is enabled (step 9).

The APK is also at `client/app/build/outputs/apk/debug/app-debug.apk` if you prefer to sideload manually via the [Downloader app](https://www.amazon.com/AFTVnews-com-Downloader/dp/B01N0BP507).

### 3. Connect

1. On the host PC: click **Start Streaming** in the host app
2. On the Fire Stick: open StreamBridge, enter the host's IP, press **Connect**
3. The stream starts automatically once the Fire Stick connects to the signaling server

### Fire Stick controls

| Button | Action |
|--------|--------|
| D-pad | Move mouse cursor (accelerates on hold) |
| Select / Enter | Left click |
| Menu | Right click |
| Back | Toggle input overlay |

---

## Project Structure

```
streambridge/
├── host/                        # Electron app — runs on the Windows PC
│   ├── main.js                  # Main process: signaling server, streamer IPC, input relay
│   ├── src/
│   │   ├── signaling.js         # WebSocket signaling server
│   │   ├── streamer.js          # FFmpeg capture + H.264 UDP sender + input receiver
│   │   └── input.js             # Input event validation and robotjs replay
│   ├── renderer/
│   │   ├── index.html           # Host UI: IP display, start button, status log
│   │   ├── renderer.js          # Renderer process: signaling wiring
│   │   └── preload.js           # Context bridge: exposes IPC to renderer
│   └── test/
│       ├── signaling.test.js    # Integration tests for the signaling relay
│       └── input.test.js        # Unit tests for input validation and replay
└── client/                      # Android app — runs on the Fire Stick
    └── app/src/main/java/com/streambridge/
        ├── MainActivity.kt      # IP entry screen
        ├── StreamActivity.kt    # Fullscreen video render
        ├── SignalingClient.kt   # WebSocket signaling (OkHttp)
        ├── UdpVideoReceiver.kt  # UDP reassembly + MediaCodec H.264 decode
        ├── ScreenVideoRenderer.kt # EGL14 + OES shader render
        └── InputHandler.kt      # D-pad → mouse/keyboard events via UDP
```

---

## Development

### Running host tests

```bash
cd host
npm test
```

Uses the Node.js built-in test runner (`node:test`) — no additional test dependencies.

### Building the Android client

```bash
cd client
./gradlew assembleDebug          # Build APK
./gradlew installDebug           # Build and push to connected ADB device
```

### Measuring latency

Set `STREAMBRIDGE_DEBUG=1` before launching the host to enable the built-in latency clock and capture tools. The clock displays milliseconds on screen (visible in the stream); compare host vs Fire Stick values. Alternatively, film both screens with a phone camera and count the frame offset (~16.7ms per frame at 60fps).

---

## Contributing

### Prerequisites

| Tool | Version | Download |
|------|---------|----------|
| Node.js | 20+ | [nodejs.org](https://nodejs.org/en/download) |
| FFmpeg | 6+ | `winget install Gyan.FFmpeg` |
| JDK | 17+ | [Microsoft OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download) or [Adoptium](https://adoptium.net) |
| Android SDK | API 34 | [Android Studio](https://developer.android.com/studio) (easiest) or [command-line tools](https://developer.android.com/studio#command-line-tools-only) |
| ADB | any | Bundled with Android Studio / SDK platform-tools |

After installing the Android SDK, point the build at it:

```bash
echo "sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk" > client/local.properties
```

### Git hooks

Commit messages must reference a YouTrack work item (`SB-N`, `BUG-N`, or `SEC-N`). The hook is in `.githooks/` and versioned with the repo. Activate it once after cloning:

```bash
git config core.hooksPath .githooks
```

Merge, Revert, `fixup!`, and `squash!` commits are exempt.

### Work item prefixes

| Prefix | Use for |
|--------|---------|
| `SB-N` | Features and tasks |
| `BUG-N` | Bug fixes |
| `SEC-N` | Security issues |

---

## Architecture

See [architecture.md](architecture.md) for the full system design: component breakdown, connection sequence, latency budget, codec configuration, and network requirements.
