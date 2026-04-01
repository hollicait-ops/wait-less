# StreamBridge — Architecture

## Goal

Stream the Windows laptop display and audio to an Amazon Fire Stick over local WiFi with glass-to-glass latency under 60ms. No cloud services, no relay servers, no new hardware purchases.

---

## System Overview

```
┌─────────────────────────────────────────────┐     WiFi (5GHz LAN)    ┌──────────────────────────────┐
│              LAPTOP (Windows)               │                         │       FIRE STICK             │
│                                             │                         │                              │
│  ┌──────────────┐    ┌────────────────────┐ │   WebRTC (SRTP/UDP)    │  ┌────────────────────────┐  │
│  │ Electron     │    │ Electron Renderer  │ │ ─────────────────────► │  │ Android App            │  │
│  │ Main Process │◄──►│ (Chromium)         │ │   H.264 video stream   │  │ (Kotlin)               │  │
│  │              │    │                    │ │   Opus audio stream    │  │                         │  │
│  │ • WS server  │    │ • desktopCapturer  │ │ ◄───────────────────── │  │ • WebRTC peer          │  │
│  │ • IPC bridge │    │ • WebRTC offerer   │ │   DataChannel (input)  │  │ • H.264 hw decoder     │  │
│  │ • robotjs    │    │ • MediaStream      │ │                         │  │ • SurfaceView render   │  │
│  └──────────────┘    └────────────────────┘ │                         │  │ • D-pad input handler  │  │
│         ▲                                   │   WebSocket (signaling) │  └────────────────────────┘  │
│         │ IPC                               │ ◄─────────────────────► │           │                  │
│         ▼                                   │                         │           ▼                  │
│  ┌──────────────┐                           │                         │  ┌────────────────────────┐  │
│  │ robotjs      │                           │                         │  │ TV Display             │  │
│  │ (input relay)│                           │                         │  │ (HDMI output)          │  │
│  └──────────────┘                           │                         │  └────────────────────────┘  │
└─────────────────────────────────────────────┘                         └──────────────────────────────┘
```

---

## Connection Sequence

```
Fire Stick App                  Signaling Server              Electron Renderer
      │                         (main.js, port 8080)                 │
      │── WS connect ──────────────────►│                            │
      │                                 │◄── WS connect (internal) ──│
      │                                 │                            │
      │                                 │◄── SDP offer ──────────────│  (createOffer after getUserMedia)
      │◄── SDP offer ───────────────────│                            │
      │                                 │                            │
      │── SDP answer ──────────────────►│                            │
      │                                 │── SDP answer ─────────────►│
      │                                 │                            │
      │── ICE candidates ──────────────►│── ICE candidates ─────────►│
      │◄── ICE candidates ──────────────│◄── ICE candidates ──────────│
      │                                 │                            │
      │◄════════════ WebRTC peer connection established ════════════►│
      │◄════════════ Video/Audio MediaStream flowing ════════════════│
      │══════════════ DataChannel (input events) ════════════════════►│
```

---

## Component Details

### Host: Electron Main Process (`main.js`)

Responsibilities:
- Spawn the Electron window (renderer)
- Run the WebSocket signaling server on port 8080
- Relay SDP and ICE candidates between the renderer and any connected client
- Listen for input events from the DataChannel (forwarded by renderer via IPC) and replay them using `robotjs`
- Display the local IP address prominently so the user can enter it on the Fire Stick

The signaling server is intentionally minimal — it only needs to relay messages between exactly two peers (one host, one client). No room management or multi-peer logic required.

```js
// Pseudocode — signaling relay
wss.on('connection', (ws) => {
  ws.on('message', (msg) => {
    // Broadcast to all other connected peers
    wss.clients.forEach(client => {
      if (client !== ws && client.readyState === WebSocket.OPEN) {
        client.send(msg);
      }
    });
  });
});
```

### Host: Electron Renderer (`renderer.js`)

Responsibilities:
- Call `desktopCapturer.getSources()` to enumerate screens
- Open a `MediaStream` from the selected source using `navigator.mediaDevices.getUserMedia` with `chromeMediaSource: 'desktop'`
- Capture system audio via `navigator.mediaDevices.getUserMedia({ audio: { mandatory: { chromeMediaSource: 'desktop' } } })`
- Create a `RTCPeerConnection`, add the video and audio tracks
- Connect to the local WebSocket signaling server
- Create and send SDP offer, handle answer, exchange ICE candidates
- Open a `RTCDataChannel` for receiving input events; forward them to main process via `ipcRenderer`

Key WebRTC config:
```js
const pc = new RTCPeerConnection({
  iceServers: [] // LAN only — no STUN/TURN needed
});

// Force H.264 in SDP offer (munge SDP before sending)
function preferH264(sdp) {
  // Move H264 codec to top of m=video line payload list
}
```

### Host: Input Relay (`robotjs`)

`robotjs` provides native mouse and keyboard control on Windows. It runs in the main process and receives normalized input events from the renderer via IPC:

```js
ipcMain.on('input-event', (event, data) => {
  const { type, x, y, button, key, dy } = data;
  const { width, height } = screen.getPrimaryDisplay().size;

  if (type === 'mousemove') {
    robot.moveMouse(Math.round(x * width), Math.round(y * height));
  } else if (type === 'click') {
    robot.mouseClick(button);
  } else if (type === 'scroll') {
    robot.scrollMouse(0, dy);
  } else if (type === 'keydown') {
    robot.keyToggle(key, 'down');
  } else if (type === 'keyup') {
    robot.keyToggle(key, 'up');
  }
});
```

### Client: Android App (Kotlin)

**MainActivity** — IP entry screen. User types the laptop's local IP (e.g. `192.168.1.42`). Launches StreamActivity on confirm.

**SignalingClient** — OkHttp WebSocket client. Connects to `ws://[ip]:8080`. Parses incoming JSON messages and dispatches SDP/ICE events to WebRTCManager.

**WebRTCManager** — Wraps `io.getstream:stream-webrtc-android`. Creates `PeerConnectionFactory`, configures H.264 codec, creates `PeerConnection` as answerer, attaches remote `VideoTrack` to `SurfaceViewRenderer`.

```kotlin
// Force H.264 preference
val videoCodecs = listOf(VideoCodecInfo(VideoCodecInfo.H264_BASELINE, emptyMap()))
val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
```

**InputHandler** — Intercepts `KeyEvent` from the Fire Stick remote:

| Fire Stick Key | Action |
|---|---|
| DPAD_CENTER / ENTER | Left mouse click |
| DPAD_UP/DOWN/LEFT/RIGHT | Move mouse cursor (speed configurable) |
| MENU | Right mouse click |
| BACK | Toggle input overlay / show IP screen |
| DPAD held | Accelerating cursor movement |

Converts events to normalized JSON and sends via DataChannel:
```kotlin
dataChannel.send(DataChannel.Buffer(
    ByteBuffer.wrap("""{"type":"mousemove","x":$nx,"y":$ny}""".toByteArray()),
    false
))
```

**StreamActivity** — Fullscreen `SurfaceViewRenderer` for video. Handles window flags to keep screen on and go edge-to-edge. Shows an optional HUD overlay with latency stats.

---

## Latency Budget

| Stage | Target | Notes |
|---|---|---|
| Screen capture | ~4ms | Electron `desktopCapturer`, one frame at 60fps = 16.7ms max |
| H.264 encoding | ~8ms | Hardware encoder (NVENC/QuickSync) preferred; software adds ~20ms |
| WebRTC packetize | ~2ms | SRTP framing |
| WiFi transmission | ~5ms | 5GHz 802.11ac on same AP |
| WebRTC jitter buffer | ~10ms | Minimum jitter buffer setting |
| H.264 decode | ~8ms | Fire Stick hardware decoder (MediaCodec) |
| Render | ~4ms | SurfaceView present |
| **Total (target)** | **~41ms** | Under 60ms threshold |

Observed real-world: 50–80ms depending on WiFi conditions and encoder path.

Fallback to software encoding (libx264) adds ~20–30ms. Detect GPU availability and warn user if hardware encoding is unavailable.

---

## Codec Configuration

**Video:** H.264 Baseline Profile, Level 4.1
- Resolution: 1920×1080 (default), configurable down to 1280×720
- Framerate: 60fps target, 30fps minimum
- Bitrate: 8–15 Mbps (WebRTC congestion control will adapt this)
- Keyframe interval: 2 seconds (balance between recovery speed and bandwidth)

**Audio:** Opus
- Sample rate: 48kHz
- Channels: Stereo
- Bitrate: 128kbps
- WebRTC built-in AEC and noise suppression enabled

---

## Network Requirements

- Same local WiFi network (no internet required)
- Both devices on 5GHz band (2.4GHz will likely exceed 60ms target)
- Laptop should be connected via WiFi (not ethernet — the Fire Stick is on WiFi, and having both on the same AP minimizes hops)
- No port forwarding needed — WebSocket signaling is LAN-only, WebRTC ICE uses local candidates only

---

## Security Considerations

This is a LAN-only tool. There is no authentication beyond knowing the laptop's IP address. Mitigations:

- Signaling server only listens on `127.0.0.1` and the local subnet interface, not `0.0.0.0` (do not expose to internet)
- Optionally implement a one-time PIN displayed on the laptop that the Fire Stick must enter before signaling proceeds
- SRTP (used by WebRTC) encrypts the media stream in transit

---

## Build & Sideloading the Fire Stick APK

1. Enable developer options on Fire Stick: Settings → My Fire TV → About → click "Fire TV Stick" 7 times
2. Enable ADB Debugging and Apps from Unknown Sources in Developer Options
3. Find the Fire Stick IP: Settings → My Fire TV → About → Network
4. Connect ADB: `adb connect <firestick-ip>:5555`
5. Build and install: `./gradlew installDebug`

The APK can also be transferred via USB OTG + file manager if ADB is not available.

---

## Future Improvements

- **Virtual gamepad overlay:** Add an on-screen D-pad/buttons for richer input when a Bluetooth controller is paired with the Fire Stick
- **Hardware encoding detection:** Auto-detect NVENC/QuickSync and fall back gracefully to software
- **Adaptive bitrate UI:** Show current bitrate and RTT in the HUD
- **Multi-monitor support:** Let user pick which display to stream from the host UI
- **Wake-on-LAN:** Fire Stick app sends WOL packet to wake the laptop before connecting
- **PIN authentication:** 4-digit PIN shown on laptop, entered on Fire Stick to authorize connection
- **mDNS discovery:** Eliminate IP entry entirely — laptop advertises `_streambridge._tcp.local` and the Fire Stick discovers it automatically
