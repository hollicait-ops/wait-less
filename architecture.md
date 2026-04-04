# StreamBridge — Architecture

## Goal

Stream the Windows laptop display and audio to an Amazon Fire Stick over local WiFi with glass-to-glass latency under 60ms. No cloud services, no relay servers, no new hardware purchases.

---

## System Overview

```
┌─────────────────────────────────────────────┐     WiFi (5GHz LAN)    ┌──────────────────────────────┐
│              LAPTOP (Windows)               │                         │       FIRE STICK             │
│                                             │                         │                              │
│  ┌──────────────┐    ┌────────────────────┐ │   H.264 UDP (port 9000)│  ┌────────────────────────┐  │
│  │ Electron     │    │ FFmpeg             │ │ ─────────────────────► │  │ Android App            │  │
│  │ Main Process │    │ (gdigrab → H.264)  │ │                         │  │ (Kotlin)               │  │
│  │              │    │                    │ │ ◄───────────────────── │  │                         │  │
│  │ • WS server  │    │ • Annex B stdout   │ │   Input events UDP 9001│  │ • UDP reassembly        │  │
│  │ • IPC bridge │    │ • NAL parser       │ │                         │  │ • MediaCodec H.264      │  │
│  │ • robotjs    │    │ • UDP framer       │ │                         │  │ • OES EGL render        │  │
│  └──────────────┘    └────────────────────┘ │                         │  │ • D-pad input handler  │  │
│         ▲                                   │   WebSocket (signaling) │  └────────────────────────┘  │
│         │ IPC                               │ ◄─────────────────────► │           │                  │
│         ▼                                   │         port 8080       │           ▼                  │
│  ┌──────────────┐                           │                         │  ┌────────────────────────┐  │
│  │ robotjs      │                           │                         │  │ TV Display             │  │
│  │ (input relay)│                           │                         │  │ (HDMI output)          │  │
│  └──────────────┘                           │                         │  └────────────────────────┘  │
└─────────────────────────────────────────────┘                         └──────────────────────────────┘
```

---

## Connection Sequence

```
Fire Stick App                  Signaling Server              Electron Main
      │                         (main.js, port 8080)                 │
      │── WS connect ──────────────────►│                            │
      │                                 │── client IP recorded ──────►│
      │◄── peer-joined (to renderer) ───│                            │
      │                                 │                            │── start FFmpeg ──► FFmpeg
      │◄── stream-info {videoPort,      │                            │                   (gdigrab)
      │         inputPort} ─────────────│◄── stream-info sent ───────│
      │                                 │                            │
      │  (open UDP socket, port 9000)   │                            │
      │                                 │                            │
      │◄════ H.264 UDP frames (port 9000) ════════════════════════════│
      │════ Input events UDP (port 9001) ════════════════════════════►│
```

---

## Component Details

### Host: Electron Main Process (`main.js`)

Responsibilities:
- Spawn the Electron window (renderer)
- Run the WebSocket signaling server on port 8080
- Record the client IP when the Fire Stick connects
- On `start-streamer` IPC: spawn `streamer.js` targeting the client IP
- Listen for UDP input events and replay them using `robotjs`
- Display the local IP address prominently so the user can enter it on the Fire Stick

### Host: Streamer (`src/streamer.js`)

Spawns FFmpeg as a child process using `gdigrab` (Windows GDI screen capture). FFmpeg encodes to H.264 Annex B and writes to stdout. The streamer:

1. Parses the Annex B stream into complete NAL unit groups (one access unit = one frame)
2. Fragments each frame into UDP packets with an 8-byte header:
   - `[0..3]` `frame_id` uint32 BE — monotonic frame counter
   - `[4..5]` `frag_idx` uint16 BE — 0-based fragment index
   - `[6..7]` `frag_count` uint16 BE — total fragments for this frame
3. Sends fragments to the client's video port (default 9000)
4. Listens on the input port (default 9001) for JSON input event datagrams

FFmpeg flags used:
- `-preset ultrafast -tune zerolatency` — minimises encode latency
- `-profile:v baseline` — Fire Stick hardware decoder guaranteed path
- `keyint=60:scenecut=0` — 1s IDR interval, no adaptive keyframes

### Host: Input Relay (`robotjs`)

`robotjs` provides native mouse and keyboard control on Windows. It runs in the main process and receives validated input events from `streamer.js` via the `onInput` callback:

```js
// Input event protocol (same JSON format as before)
{ "type": "mousemove", "x": 0.5, "y": 0.3 }   // normalised 0–1 floats
{ "type": "click",    "button": "left" }
{ "type": "scroll",   "dy": -3 }
{ "type": "keydown",  "key": "Return" }
```

### Client: Android App (Kotlin)

**MainActivity** — IP entry screen. User types the laptop's local IP (e.g. `192.168.1.42`). Launches StreamActivity on confirm.

**SignalingClient** — OkHttp WebSocket client. Connects to `ws://[ip]:8080`. On connect, sends `client-ready`. Receives `stream-info` containing the UDP video and input ports.

**UdpVideoReceiver** — Reassembles fragmented H.264 frames from UDP, feeds complete Annex B frames to `MediaCodec` hardware decoder. Decoded frames are written directly to a `SurfaceTexture` (no GPU→CPU readback). Also owns the UDP socket used to send input events back to the host.

**ScreenVideoRenderer** — `SurfaceView` with manual EGL14 context and a dedicated render `HandlerThread`. Creates an OES `SurfaceTexture` for MediaCodec to decode into. On each decoded frame the `OnFrameAvailableListener` fires, `updateTexImage()` is called on the GL thread, and the OES texture is drawn through a GLSL shader that applies green-channel gain and black-lift correction.

**InputHandler** — Intercepts `KeyEvent` from the Fire Stick remote:

| Fire Stick Key | Action |
|---|---|
| DPAD_CENTER / ENTER | Left mouse click |
| DPAD_UP/DOWN/LEFT/RIGHT | Move mouse cursor (accelerating on hold) |
| MENU | Right mouse click |

Converts events to JSON and sends via `UdpVideoReceiver.sendInputEvent()`.

**StreamActivity** — Fullscreen `ScreenVideoRenderer`. Wires `SignalingClient` → `UdpVideoReceiver` on `onStreamInfo`. Keeps screen on.

---

## Latency Budget

| Stage | Target | Notes |
|---|---|---|
| Screen capture | ~4ms | FFmpeg gdigrab, one frame at 60fps = 16.7ms max |
| H.264 encoding | ~8ms | Software libx264 ultrafast+zerolatency; hardware (NVENC) would be ~2ms |
| UDP packetise + send | ~1ms | No SRTP overhead, no jitter buffer |
| WiFi transmission | ~5ms | 5GHz 802.11ac on same AP |
| UDP receive + reassemble | ~1ms | In-process, no JVM GC pressure on fast path |
| H.264 decode | ~8ms | Fire Stick MediaCodec hardware decoder |
| Render | ~4ms | SurfaceTexture → OES shader → eglSwapBuffers |
| **Total (target)** | **~31ms** | Well under 60ms threshold |

Primary improvement over WebRTC: elimination of the jitter buffer (~10–50ms on LAN).

---

## Codec Configuration

**Video:** H.264 Baseline Profile, Level 4.1
- Resolution: 1920×1080 (fixed)
- Framerate: 60fps
- Preset: `ultrafast`, tune: `zerolatency`
- Keyframe interval: 60 frames (1 second)
- No B-frames (baseline profile enforces this)

**Audio:** Not yet implemented (video-only in SB-21).

---

## UDP Packet Format

```
 0               1               2               3
 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
├───────────────────────────────────────────────────────────────────┤
│                         frame_id (uint32 BE)                      │
├───────────────────────────────────────────────────────────────────┤
│         frag_idx (uint16 BE)          │      frag_count (uint16)  │
├───────────────────────────────────────────────────────────────────┤
│                    H.264 Annex B payload (≤1392 bytes)            │
│                              ...                                  │
└───────────────────────────────────────────────────────────────────┘
```

Client reassembles fragments by `frame_id`. Incomplete frames (due to packet loss) are silently dropped — the next IDR will restore a clean picture within 1 second.

---

## Network Requirements

- Same local WiFi network (no internet required)
- Both devices on 5GHz band (2.4GHz will likely exceed 60ms target)
- Ports used: 8080 (TCP WebSocket signaling), 9000 (UDP video), 9001 (UDP input)
- No port forwarding needed — all communication is LAN-only

---

## Security Considerations

This is a LAN-only tool. There is no authentication beyond knowing the laptop's IP address. Mitigations:

- Signaling server only listens on the local network interface, not exposed to internet
- UDP sockets are LAN-only by design
- Optionally implement a one-time PIN displayed on the laptop that the Fire Stick must enter before stream-info is sent

---

## Build & Sideloading the Fire Stick APK

1. Enable developer options on Fire Stick: Settings → My Fire TV → About → click "Fire TV Stick" 7 times
2. Enable ADB Debugging and Apps from Unknown Sources in Developer Options
3. Find the Fire Stick IP: Settings → My Fire TV → About → Network
4. Connect ADB: `adb connect <firestick-ip>:5555`
5. Build and install: `./gradlew installDebug`

---

## Future Improvements

- **Audio streaming:** Capture system audio via FFmpeg and multiplex over UDP alongside video
- **Hardware encoding:** Auto-detect NVENC/QuickSync on the host; fall back to libx264
- **Adaptive bitrate:** Reduce bitrate on packet loss detection; expose bitrate in HUD
- **Multi-monitor support:** Let user pick which display to stream from the host UI
- **mDNS discovery:** Eliminate IP entry — laptop advertises `_streambridge._tcp.local`, Fire Stick discovers it automatically
- **PIN authentication:** 4-digit PIN shown on laptop, entered on Fire Stick to authorize connection
- **Wake-on-LAN:** Fire Stick app sends WOL packet to wake the laptop before connecting
