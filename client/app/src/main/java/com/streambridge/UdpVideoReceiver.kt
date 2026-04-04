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
    private var drainThread: Thread? = null
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
        codecThread  = Thread(::codecFeedLoop, "codec-feed").also { it.isDaemon = true; it.start() }
        drainThread  = Thread(::drainLoop, "codec-drain").also { it.isDaemon = true; it.start() }

        onStatus("Receiving stream on UDP port $videoPort")
    }

    fun stop() {
        running = false
        receiveThread?.interrupt()
        codecThread?.interrupt()
        drainThread?.interrupt()
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

        // Don't feed the codec until we've seen a complete IDR keyframe.
        // The IDR is large (many fragments) and slow to assemble; P-frames
        // complete first and would corrupt the codec state if fed without prior CSD.
        var waitingForKeyframe = true

        var firstPacket = true
        while (running) {
            try {
                videoSocket!!.receive(packet)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "receive error: ${e.message}")
                break
            }
            if (firstPacket) {
                Log.i(TAG, "First UDP packet received (${packet.length} bytes)")
                firstPacket = false
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

                if (waitingForKeyframe) {
                    if (!containsIdr(frame)) {
                        // P-frame arrived before the IDR is fully assembled — drop it
                        continue
                    }
                    waitingForKeyframe = false
                    Log.i(TAG, "First IDR keyframe assembled (frame $frameId, ${frame.size} bytes)")
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

    /** Returns true if the Annex B buffer contains a NAL unit of type 5 (IDR slice). */
    private fun containsIdr(data: ByteArray): Boolean {
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                val bodyIdx = i + 4
                if (bodyIdx < data.size && (data[bodyIdx].toInt() and 0x1f) == 5) return true
                i = bodyIdx + 1
            } else {
                i++
            }
        }
        return false
    }

    // ---------------------------------------------------------------------------
    // MediaCodec feed loop — pulls complete frames and queues to decoder
    // ---------------------------------------------------------------------------

    private fun codecFeedLoop() {
        val mc = codec ?: return
        var frameCount = 0
        while (running) {
            val frame = try {
                frameQueue.take()
            } catch (e: InterruptedException) {
                break
            }
            frameCount++
            if (frameCount <= 3) {
                Log.i(TAG, "Frame #$frameCount: ${frame.size} bytes NALs=[${describeAnnexBNalTypes(frame)}]")
            }
            if (!feedAnnexBFrame(mc, frame)) break
        }
    }

    /**
     * Feeds an Annex B frame to MediaCodec, splitting SPS (type 7) and PPS (type 8)
     * out as BUFFER_FLAG_CODEC_CONFIG buffers so hardware decoders that require
     * explicit CSD initialisation are satisfied. All remaining NAL units (slices,
     * SEI, etc.) are concatenated and submitted as a single regular input buffer.
     */
    private fun feedAnnexBFrame(mc: MediaCodec, data: ByteArray): Boolean {
        val nals = splitAnnexB(data)

        // Send SPS/PPS as codec-config buffers
        for ((nalType, nalData) in nals) {
            if (nalType == 7 || nalType == 8) {
                if (!feedBuffer(mc, nalData, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) return false
            }
        }

        // Send remaining NALs (slices, SEI, AUD…) as a single regular buffer
        val sliceNals = nals.filter { (t, _) -> t != 7 && t != 8 }
        if (sliceNals.isNotEmpty()) {
            val combined = ByteArray(sliceNals.sumOf { it.second.size })
            var pos = 0
            for ((_, nalData) in sliceNals) {
                nalData.copyInto(combined, pos)
                pos += nalData.size
            }
            if (!feedBuffer(mc, combined, 0)) return false
        }
        return true
    }

    /** Splits an Annex B buffer into (nalType, rawBytesWithStartCode) pairs. */
    private fun splitAnnexB(data: ByteArray): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var nalStart = -1
        var nalBodyOffset = -1
        var i = 0
        while (i < data.size) {
            val rem = data.size - i
            val sc4 = rem >= 4 && data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                       data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
            val sc3 = !sc4 && rem >= 3 && data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                       data[i+2] == 1.toByte()
            if (sc4 || sc3) {
                if (nalBodyOffset >= 0) {
                    val nalData = data.copyOfRange(nalStart, i)
                    val nalType = data[nalBodyOffset].toInt() and 0x1f
                    result.add(Pair(nalType, nalData))
                }
                nalStart = i
                nalBodyOffset = i + (if (sc4) 4 else 3)
                i = nalBodyOffset + 1
            } else {
                i++
            }
        }
        if (nalBodyOffset >= 0) {
            val nalData = data.copyOfRange(nalStart, data.size)
            val nalType = data[nalBodyOffset].toInt() and 0x1f
            result.add(Pair(nalType, nalData))
        }
        return result
    }

    private fun describeAnnexBNalTypes(data: ByteArray): String {
        return splitAnnexB(data).joinToString(",") { (t, _) -> t.toString() }
    }

    // ---------------------------------------------------------------------------
    // Dedicated output drain loop — runs on its own thread so it can block on
    // dequeueOutputBuffer without starving the input feed thread.
    // ---------------------------------------------------------------------------

    private fun drainLoop() {
        val mc = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val outputIdx = try {
                mc.dequeueOutputBuffer(info, 10_000L) // block up to 10 ms per frame
            } catch (e: android.media.MediaCodec.CodecException) {
                if (running) Log.w(TAG, "drain CodecException: code=${e.errorCode} diag=${e.diagnosticInfo} recoverable=${e.isRecoverable}")
                break
            } catch (e: Exception) {
                if (running) Log.w(TAG, "drain error: ${e::class.java.simpleName} — ${e.message}", e)
                break
            }
            when {
                outputIdx >= 0 -> {
                    mc.releaseOutputBuffer(outputIdx, true) // render to Surface
                    framesRendered++
                    if (framesRendered <= 5 || framesRendered % 60 == 0) {
                        Log.i(TAG, "Released frame #$framesRendered to Surface (pts=${info.presentationTimeUs})")
                    }
                }
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Output format changed: ${mc.outputFormat}")
                }
                // INFO_TRY_AGAIN_LATER: loop and wait for the next frame
            }
        }
    }

    /** Queues [data] to the codec with the given [flags]. Returns false on fatal error. */
    private fun feedBuffer(mc: MediaCodec, data: ByteArray, flags: Int): Boolean {
        val inputIdx = try {
            mc.dequeueInputBuffer(10_000L) // 10 ms timeout
        } catch (e: Exception) {
            Log.w(TAG, "dequeueInputBuffer error: ${e.message}")
            return false
        }
        if (inputIdx < 0) return true // timeout — skip this data

        val inputBuf = mc.getInputBuffer(inputIdx) ?: return true
        val written = minOf(data.size, inputBuf.capacity())
        inputBuf.clear()
        inputBuf.put(data, 0, written)
        mc.queueInputBuffer(inputIdx, 0, written, System.nanoTime() / 1000L, flags)
        return true
    }

    private var framesRendered = 0

    // ---------------------------------------------------------------------------
    // MediaCodec setup
    // ---------------------------------------------------------------------------

    private fun createAndStartCodec(surface: Surface): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).also {
            // CSD (codec-specific data / SPS+PPS) is embedded inline in the Annex B
            // stream by FFmpeg, so we don't provide KEY_CSD_0/KEY_CSD_1 here.
            it.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
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
