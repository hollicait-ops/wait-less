package com.streambridge

import android.content.Context
import android.util.Log
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
    }

    /** Set by StreamActivity after both WebRTCManager and SignalingClient are created. */
    var onSendSignaling: ((JSONObject) -> Unit)? = null

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    // DataChannel for sending input events back to the host (wired in SB-10)
    var dataChannel: DataChannel? = null

    fun start() {
        val base = EglBase.create()
        eglBase = base

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(base.eglBaseContext))
            .createPeerConnectionFactory()

        renderer.init(base.eglBaseContext, null)
        renderer.setMirror(false)
    }

    fun onRemoteOffer(offer: JSONObject) {
        val pc = getOrCreatePeerConnection() ?: run {
            Log.e(TAG, "Failed to create peer connection")
            onStatus("Stream error: could not create connection")
            return
        }

        val remoteSdp = SessionDescription(
            SessionDescription.Type.OFFER,
            offer.optString("sdp"),
        )

        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                val answerJson = JSONObject().apply {
                                    put("type", "answer")
                                    put("sdp", answer.description)
                                }
                                onSendSignaling?.invoke(answerJson)
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
        // TODO (SB-10): send via dataChannel
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
                val json = JSONObject().apply {
                    put("type", "ice-candidate")
                    put("candidate", JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                }
                onSendSignaling?.invoke(json)
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
