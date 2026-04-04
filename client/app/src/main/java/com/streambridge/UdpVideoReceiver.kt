package com.streambridge

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue

/**
 * Receives fragmented H.264 UDP packets from the host, reassembles frames,
 * and decodes them to an output [Surface] via MediaCodec hardware decoder.
 *
 * UDP packet header (8 bytes, big-endian):
 *   [0..3]  frame_id   uint32 — monotonic frame counter
 *   [4..5]  frag_idx   uint16 — 0-based fragment index within frame
 *   [6..7]  frag_count uint16 — total fragments in this frame
 *
 * Also owns the UDP socket used to send input events back to the host.
 */
class UdpVideoReceiver(
    private val outputSurface: Surface,
    private val hostIp: String,
    private val videoPort: Int,
    private val inputPort: Int,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        private const val TAG = "UdpVideoReceiver"
        private const val MAX_PACKET = 1500
        private const val HEADER_SIZE = 8
        // Frames queued between reassembly and MediaCodec input thread
        private const val FRAME_QUEUE_CAPACITY = 8
        // Stale frame cleanup: if frame_id gap grows beyond this, drop old partials
        private const val MAX_PENDING_FRAMES = 4
    }

    // Reassembled Annex B frames ready for MediaCodec
    private val frameQueue = ArrayBlockingQueue<ByteArray>(FRAME_QUEUE_CAPACITY)

    private var receiveThread: Thread? = null
    private var codecThread: Thread? = null
    private var codec: MediaCodec? = null
    private var videoSocket: DatagramSocket? = null
    private var inputSocket: DatagramSocket? = null

    @Volatile private var running = false

    fun start() {
        running = true

        videoSocket = DatagramSocket(videoPort)
        inputSocket = DatagramSocket()

        codec = createAndStartCodec(outputSurface)

        receiveThread = Thread(::receiveLoop, "udp-receive").also { it.isDaemon = true; it.start() }
        codecThread = Thread(::codecFeedLoop, "codec-feed").also { it.isDaemon = true; it.start() }

        onStatus("Receiving stream on UDP port $videoPort")
    }

    fun stop() {
        running = false
        receiveThread?.interrupt()
        codecThread?.interrupt()
        videoSocket?.close()
        inputSocket?.close()
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "codec release error: ${e.message}")
        }
        codec = null
        videoSocket = null
        inputSocket = null
    }

    fun sendInputEvent(json: JSONObject) {
        val socket = inputSocket ?: return
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val addr = InetAddress.getByName(hostIp)
        val packet = DatagramPacket(bytes, bytes.size, addr, inputPort)
        try {
            socket.send(packet)
        } catch (e: Exception) {
            Log.w(TAG, "input send error: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------
    // UDP receive + frame reassembly
    // ---------------------------------------------------------------------------

    private fun receiveLoop() {
        val buf = ByteArray(MAX_PACKET)
        val packet = DatagramPacket(buf, buf.size)
        // pending[frameId] = array of nullable fragment byte arrays
        val pending = LinkedHashMap<Long, Array<ByteArray?>>()

        while (running) {
            try {
                videoSocket!!.receive(packet)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "receive error: ${e.message}")
                break
            }

            val data = packet.data
            val len = packet.length
            if (len < HEADER_SIZE) continue

            val frameId = readUInt32BE(data, 0)
            val fragIdx = readUInt16BE(data, 4)
            val fragCount = readUInt16BE(data, 6)

            if (fragCount == 0 || fragIdx >= fragCount) continue

            val frags = pending.getOrPut(frameId) { arrayOfNulls(fragCount) }
            if (frags.size != fragCount) {
                // Mismatched frag_count — corrupted or reused frame_id, discard
                pending.remove(frameId)
                continue
            }

            val payload = ByteArray(len - HEADER_SIZE)
            System.arraycopy(data, HEADER_SIZE, payload, 0, payload.size)
            frags[fragIdx] = payload

            // Check if all fragments have arrived
            if (frags.all { it != null }) {
                pending.remove(frameId)
                val frame = ByteArray(frags.sumOf { it!!.size })
                var pos = 0
                for (frag in frags) {
                    frag!!.copyInto(frame, pos)
                    pos += frag.size
                }
                // Drop oldest rather than block if the codec is behind
                if (!frameQueue.offer(frame)) {
                    frameQueue.poll()
                    frameQueue.offer(frame)
                }
            }

            // Drop stale partials to prevent unbounded growth
            if (pending.size > MAX_PENDING_FRAMES) {
                val oldest = pending.keys.first()
                pending.remove(oldest)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // MediaCodec feed loop — pulls complete frames and queues to decoder
    // ---------------------------------------------------------------------------

    private fun codecFeedLoop() {
        val mc = codec ?: return
        while (running) {
            val frame = try {
                frameQueue.take()
            } catch (e: InterruptedException) {
                break
            }

            val inputIdx = try {
                mc.dequeueInputBuffer(10_000L) // 10 ms timeout
            } catch (e: Exception) {
                Log.w(TAG, "dequeueInputBuffer error: ${e.message}")
                break
            }

            if (inputIdx < 0) continue // timeout — try again

            val inputBuf = mc.getInputBuffer(inputIdx) ?: continue
            val written = minOf(frame.size, inputBuf.capacity())
            inputBuf.clear()
            inputBuf.put(frame, 0, written)
            mc.queueInputBuffer(inputIdx, 0, written, System.nanoTime() / 1000L, 0)

            // Drain output buffers — with Surface output, releaseOutputBuffer(render=true)
            // pushes the decoded frame to the SurfaceTexture automatically.
            drainOutput(mc)
        }
    }

    private fun drainOutput(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIdx = mc.dequeueOutputBuffer(info, 0L)
            when {
                outputIdx >= 0 -> mc.releaseOutputBuffer(outputIdx, true) // render to Surface
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Output format changed: ${mc.outputFormat}")
                }
                else -> break // INFO_TRY_AGAIN_LATER or INFO_OUTPUT_BUFFERS_CHANGED
            }
        }
    }

    // ---------------------------------------------------------------------------
    // MediaCodec setup
    // ---------------------------------------------------------------------------

    private fun createAndStartCodec(surface: Surface): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).also {
            // CSD (codec-specific data / SPS+PPS) is embedded inline in the Annex B
            // stream by FFmpeg, so we don't provide KEY_CSD_0/KEY_CSD_1 here.
            it.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
        }
        val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mc.configure(format, surface, null, 0)
        mc.start()
        Log.i(TAG, "MediaCodec H.264 decoder started")
        return mc
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun readUInt32BE(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 24) or
               ((buf[offset + 1].toLong() and 0xFF) shl 16) or
               ((buf[offset + 2].toLong() and 0xFF) shl 8) or
               (buf[offset + 3].toLong() and 0xFF)
    }

    private fun readUInt16BE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }
}
