package com.streambridge

import org.json.JSONObject

/**
 * Represents a single input event to be sent over the DataChannel.
 * All coordinates are normalised to [0.0, 1.0].
 * No Android imports — fully testable on JVM.
 */
sealed class InputEvent {
    abstract fun toJson(): JSONObject

    data class MouseMove(val x: Float, val y: Float) : InputEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "mousemove")
            put("x", x.toDouble())
            put("y", y.toDouble())
        }
    }

    data class Click(val button: String) : InputEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "click")
            put("button", button)
        }
    }

    data class Scroll(val dy: Int) : InputEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "scroll")
            put("dy", dy)
        }
    }

    data class KeyDown(val key: String) : InputEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "keydown")
            put("key", key)
        }
    }

    data class KeyUp(val key: String) : InputEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "keyup")
            put("key", key)
        }
    }
}
