package com.streambridge

import android.content.Context
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer

/**
 * Manages the WebRTC peer connection as an answerer.
 * Receives SDP offer from the host, creates an answer, exchanges ICE candidates,
 * and attaches the incoming H.264 video track to the SurfaceViewRenderer.
 *
 * Full implementation in SB-9.
 */
class WebRTCManager(
    private val context: Context,
    private val renderer: SurfaceViewRenderer,
    private val onStatus: (String) -> Unit,
) {
    // DataChannel used to send input events back to the host
    private var dataChannel: org.webrtc.DataChannel? = null

    fun start() {
        // TODO (SB-9): initialise PeerConnectionFactory, EGL context, SurfaceViewRenderer
    }

    fun onRemoteOffer(offer: JSONObject) {
        // TODO (SB-9): setRemoteDescription, createAnswer, setLocalDescription, send answer via SignalingClient
    }

    fun onRemoteAnswer(answer: JSONObject) {
        // TODO (SB-9): setRemoteDescription with answer (answerer role, so this path is unused in normal flow)
    }

    fun onRemoteIceCandidate(msg: JSONObject) {
        // TODO (SB-9): addIceCandidate
    }

    fun sendDataChannelMessage(json: JSONObject) {
        // TODO (SB-10): send via dataChannel
    }

    fun dispose() {
        // TODO (SB-9): close peer connection, release renderer and EGL context
    }
}
