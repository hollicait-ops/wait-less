// capture.js — Screen/audio capture (renderer process)
// Uses desktopCapturer source IDs relayed from the main process via IPC.

async function startCapture() {
  const sources = await streambridge.getDesktopSources();
  if (!sources || sources.length === 0) {
    throw new Error('No screen sources available');
  }

  // Prefer the first screen source; 'screen:' prefix identifies display captures
  const screenSource = sources.find(s => s.id.startsWith('screen:')) || sources[0];

  // Try with system audio (Windows loopback capture); fall back to video-only
  try {
    return await navigator.mediaDevices.getUserMedia({
      audio: {
        mandatory: {
          chromeMediaSource: 'desktop',
        },
      },
      video: {
        mandatory: {
          chromeMediaSource: 'desktop',
          chromeMediaSourceId: screenSource.id,
          maxWidth: 1920,
          maxHeight: 1080,
          maxFrameRate: 60,
        },
      },
    });
  } catch {
    return await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: {
        mandatory: {
          chromeMediaSource: 'desktop',
          chromeMediaSourceId: screenSource.id,
          maxWidth: 1920,
          maxHeight: 1080,
          maxFrameRate: 60,
        },
      },
    });
  }
}
