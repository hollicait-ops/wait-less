package com.streambridge

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class WebRTCManager(
    private val context: Context,
    private val renderer: SurfaceViewRenderer,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        private const val TAG = "WebRTCManager"
        @Volatile private var factoryInitialized = false
    }

    /** Set by StreamActivity after both WebRTCManager and SignalingClient are created. */
    var onSendSignaling: ((JSONObject) -> Unit)? = null

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    // DataChannel for sending input events back to the host (wired in SB-10)
    var dataChannel: DataChannel? = null
        private set

    fun start() {
        val base = EglBase.create()
        eglBase = base

        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(base.eglBaseContext))
            .createPeerConnectionFactory()

        renderer.init(base.eglBaseContext, null)
        renderer.setMirror(false)
    }

    fun onRemoteOffer(offer: JSONObject) {
        Log.i(TAG, "onRemoteOffer: sdp length=${offer.optString("sdp").length}")
        val pc = getOrCreatePeerConnection() ?: run {
            Log.e(TAG, "Failed to create peer connection")
            onStatus("Stream error: could not create connection")
            return
        }

        val sdpString = offer.optString("sdp")
        sdpString.lines().filter { it.contains("profile-level-id", ignoreCase = true) || it.startsWith("b=AS:") }
            .forEach { Log.i(TAG, "SDP offer: $it") }

        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)

        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.i(TAG, "setRemoteDescription succeeded — creating answer")
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        Log.i(TAG, "createAnswer succeeded — setting local description")
                        answer.description.lines()
                            .filter { it.contains("profile-level-id", ignoreCase = true) || it.startsWith("b=AS:") }
                            .forEach { Log.i(TAG, "SDP answer: $it") }
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                Log.i(TAG, "setLocalDescription succeeded — sending answer")
                                val answerJson = JSONObject().apply {
                                    put("type", "answer")
                                    put("sdp", answer.description)
                                }
                                onSendSignaling?.invoke(answerJson)
                                    ?: Log.w(TAG, "onSendSignaling not set — answer discarded")
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "setLocalDescription failed: $error")
                                onStatus("Stream error: local description rejected")
                            }
                        }, answer)
                    }
                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "createAnswer failed: $error")
                        onStatus("Stream error: failed to create answer")
                    }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription failed: $error")
                onStatus("Stream error: remote description rejected")
            }
        }, remoteSdp)
    }

    fun onRemoteAnswer(answer: JSONObject) {
        // We are the answerer in the normal flow — this is a no-op unless the host re-offers.
    }

    fun onRemoteIceCandidate(msg: JSONObject) {
        val candidateObj = msg.optJSONObject("candidate") ?: return
        val candidate = IceCandidate(
            candidateObj.optString("sdpMid"),
            candidateObj.optInt("sdpMLineIndex"),
            candidateObj.optString("candidate"),
        )
        peerConnection?.addIceCandidate(candidate)
    }

    fun sendDataChannelMessage(json: JSONObject) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    fun dispose() {
        peerConnection?.dispose()
        peerConnection = null
        factory?.dispose()
        factory = null
        renderer.release()
        eglBase?.release()
        eglBase = null
    }

    private fun getOrCreatePeerConnection(): PeerConnection? {
        peerConnection?.let { return it }

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // LAN-only — no TURN or STUN server needed
        }

        return factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE connection state: $state")
                val status = when (state) {
                    PeerConnection.IceConnectionState.CONNECTED     -> "Streaming"
                    PeerConnection.IceConnectionState.DISCONNECTED  -> "Stream disconnected"
                    PeerConnection.IceConnectionState.FAILED        -> "Stream connection failed"
                    else -> return
                }
                onStatus(status)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Local ICE candidate: ${candidate.sdp.take(80)}")
                val json = JSONObject().apply {
                    put("type", "ice-candidate")
                    put("candidate", JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                }
                onSendSignaling?.invoke(json)
                    ?: Log.w(TAG, "onSendSignaling not set — local ICE candidate dropped")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onAddStream(stream: MediaStream) {}

            override fun onRemoveStream(stream: MediaStream) {}

            override fun onDataChannel(dc: DataChannel) {
                dataChannel = dc
            }

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    track.addSink(renderer)
                }
            }
        })?.also { peerConnection = it }
    }
}

/**
 * No-op base for SdpObserver — only override the methods you care about.
 */
private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
