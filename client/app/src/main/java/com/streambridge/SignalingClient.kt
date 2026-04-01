package com.streambridge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Message parsing — no Android imports, fully unit-testable
// ---------------------------------------------------------------------------

sealed class SignalingMessage {
    data class Offer(val json: JSONObject) : SignalingMessage()
    data class Answer(val json: JSONObject) : SignalingMessage()
    data class IceCandidate(val json: JSONObject) : SignalingMessage()
}

/**
 * Parse a raw WebSocket text frame into a [SignalingMessage].
 * Returns null if the text is not valid JSON, has no "type" field,
 * or carries an unrecognised type.
 */
fun parseSignalingMessage(text: String): SignalingMessage? {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
    return when (json.optString("type")) {
        "offer"         -> SignalingMessage.Offer(json)
        "answer"        -> SignalingMessage.Answer(json)
        "ice-candidate" -> SignalingMessage.IceCandidate(json)
        else            -> null
    }
}

// ---------------------------------------------------------------------------
// SignalingClient
// ---------------------------------------------------------------------------

/**
 * WebSocket signaling client. Connects to the Electron host signaling server,
 * parses incoming SDP/ICE messages, and dispatches them to [WebRTCManager].
 * Connection state changes are reported via [Listener].
 */
class SignalingClient(
    private val url: String,
    private val webRtcManager: WebRTCManager,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onError(message: String)
    }

    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val msg = parseSignalingMessage(text)) {
                    is SignalingMessage.Offer         -> webRtcManager.onRemoteOffer(msg.json)
                    // Answer path is unused in normal flow (we are the answerer, not the offerer),
                    // but forwarded for completeness in case of a re-offer scenario.
                    is SignalingMessage.Answer        -> webRtcManager.onRemoteAnswer(msg.json)
                    is SignalingMessage.IceCandidate  -> webRtcManager.onRemoteIceCandidate(msg.json)
                    null                              -> { /* unrecognised — ignore */ }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Unknown error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected(reason.ifEmpty { "Connection closed" })
            }
        })
    }

    fun send(json: JSONObject) {
        ws?.send(json.toString())
    }

    fun disconnect() {
        ws?.close(1000, "Activity destroyed")
        // Do not shut down client.dispatcher.executorService — OkHttp manages its own
        // thread pool lifecycle and shutting it down would break any reconnect attempt.
    }
}
