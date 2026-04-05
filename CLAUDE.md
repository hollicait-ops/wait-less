# WaitLess — Claude Code Instructions

WaitLess is a low-latency wireless screen streaming app. It streams the Windows host PC display and audio to a Fire Stick over a local WiFi network. Measured glass-to-glass latency is ~120-150ms. The architecture mirrors Steam Link: a host process on the PC captures, encodes, and streams; a native Android app on the Fire Stick decodes and renders.

**Latency budget (measured):** ~130ms gdigrab capture, ~7ms client decode, ~5ms WiFi/UDP. The gdigrab GDI capture is the dominant bottleneck; DXGI (ddagrab) would reduce this but requires D3D11-to-CUDA interop not currently available.

---

## Project Structure

```
waitless/
├── CLAUDE.md                  # This file
├── architecture.md            # Full system design
├── .claude/
│   └── agents/
│       └── validate-setup.md  # Sub-agent: validates dev environment
├── host/                      # Electron app — runs on Windows host PC
│   ├── package.json
│   ├── main.js                # Main process: signaling server + app lifecycle
│   ├── src/
│   │   ├── signaling.js       # WebSocket signaling server (ws library)
│   │   ├── capture.js         # Screen/audio capture via desktopCapturer
│   │   └── peer.js            # WebRTC peer connection management
│   └── renderer/
│       ├── index.html         # Minimal UI: source picker, status, IP display
│       └── renderer.js        # Renderer process: WebRTC offerer
└── client/                    # Android app — runs on Fire Stick
    ├── build.gradle
    ├── app/
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       └── java/com/waitless/
    │           ├── MainActivity.kt        # Entry point, IP input screen
    │           ├── StreamActivity.kt      # Fullscreen video render
    │           ├── SignalingClient.kt     # WebSocket signaling (OkHttp)
    │           ├── WebRTCManager.kt       # Peer connection, codec config
    │           └── InputHandler.kt        # D-pad → mouse/keyboard events
    └── res/
```

---

## Tech Stack

| Layer | Choice | Reason |
|---|---|---|
| Host runtime | Electron (Node.js + Chromium) | `desktopCapturer` API, full WebRTC in renderer, no native modules |
| Screen capture | Electron `desktopCapturer` | Built-in, no FFmpeg dependency for capture |
| Video codec | H.264 (hardware preferred) | Universal hardware decode on Fire Stick, low latency |
| Transport | WebRTC (SRTP over UDP) | Sub-100ms, jitter buffering, built-in congestion control |
| Signaling | WebSocket (`ws` npm package) | Simple, no external broker needed on LAN |
| Client runtime | Android (Kotlin) | Fire OS = Android; sideload APK, native WebRTC SDK |
| WebRTC (client) | `io.getstream:stream-webrtc-android` | Maintained Google libwebrtc fork with Kotlin API |
| Input channel | WebRTC DataChannel | Bidirectional, same connection as video |

---

## Commands

### Host (Electron)

```bash
cd host
npm install
npm run start        # Start in dev mode
npm run build        # Package as Windows .exe (electron-builder)
npm run lint         # ESLint
```

### Client (Android)

```bash
cd client
./gradlew assembleDebug           # Build debug APK
./gradlew installDebug            # Install to connected ADB device
adb connect <firestick-ip>:5555   # Connect to Fire Stick over WiFi ADB
adb install app/build/outputs/apk/debug/app-debug.apk
```

Enable ADB on Fire Stick: Settings → My Fire TV → Developer Options → ADB Debugging ON, Apps from Unknown Sources ON.

---

## Development Workflow

1. Start with `host/` — get the Electron app capturing and serving a WebRTC offer
2. Test the stream in a local Chrome tab first (the receiver page at `localhost:3000`) before touching Android
3. Once Chrome receives the stream cleanly, build the Android client
4. Use `adb connect` over WiFi to deploy to the Fire Stick without USB

### Testing Latency

The Electron UI has a built-in latency clock and dual-screen capture tool (hidden by default). Enable with `WAITLESS_DEBUG=1` environment variable before launching. The clock displays milliseconds on screen (captured by gdigrab into the stream); compare host vs Fire Stick values for glass-to-glass latency. The "Capture latency" button takes simultaneous screenshots of both screens, but adb timing variance makes phone-camera comparison more accurate for precise measurements.

Pipeline instrumentation logging (queue wait, decode time, chunk gaps) is also gated behind debug mode. Check Electron status log and `adb logcat -s UdpVideoReceiver:V` for `[latency]` entries.

---

## Key Constraints & Gotchas

- **Codec negotiation:** Force H.264 on both sides. Fire Stick hardware decodes H.264; H.265/VP9 may fall back to software and add latency. Set `preferredVideoCodec` in SDP munging if needed.
- **Audio sync:** Use WebRTC's built-in AEC and audio sync — don't implement custom A/V sync.
- **Electron screen capture:** `desktopCapturer` only works in the renderer process. The main process must pass the source ID via IPC. Do not try to capture in the main process.
- **Fire Stick resolution:** Cap at 1080p@60fps. 4K streaming over WiFi will cause buffering.
- **WiFi band:** Both devices must be on 5GHz for acceptable latency. Detect and warn if on 2.4GHz.
- **TURN server:** Not needed — this is a LAN-only app. Use only STUN (or no ICE server at all since both peers are on the same subnet).
- **Input events:** DataChannel messages use a simple JSON protocol: `{ type: "mousemove", x: 0.5, y: 0.3 }` with coordinates as 0–1 normalized floats. The host uses `robotjs` to replay them.
- **Fire Stick remote:** The D-pad and select button are the primary inputs. Back button should toggle the input overlay. Map center/select to left click.

---

## Input Event Protocol (DataChannel)

```json
{ "type": "mousemove", "x": 0.612, "y": 0.341 }
{ "type": "click", "button": "left" }
{ "type": "click", "button": "right" }
{ "type": "scroll", "dy": -3 }
{ "type": "keydown", "key": "Return" }
{ "type": "keyup", "key": "Return" }
```

---

## Environment Requirements

- Node.js 20+
- Electron 28+
- **FFmpeg on PATH** (`winget install Gyan.FFmpeg`) — required for screen capture and H.264 encoding; the streamer will fail silently without it
- Android Studio (for client builds) or just the Android SDK + Gradle
- `adb` on PATH
- Fire Stick with ADB debugging enabled (see above)
- Both host PC and Fire Stick on the same WiFi network (5GHz preferred)
