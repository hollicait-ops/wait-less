package com.streambridge

import android.view.KeyEvent

/**
 * Bridges Android KeyEvent codes to InputMapper and sends resulting
 * InputEvents over the WebRTC DataChannel.
 */
class InputHandler(private val webRtcManager: WebRTCManager) {

    private val mapper = InputMapper()

    // Single source of truth for direction keys — both isDpadDirection and
    // keyCodeToEvent derive from this map, so adding a new direction key here
    // is the only change needed.
    private val dpadToDirection = mapOf(
        KeyEvent.KEYCODE_DPAD_UP    to InputMapper.Direction.UP,
        KeyEvent.KEYCODE_DPAD_DOWN  to InputMapper.Direction.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT  to InputMapper.Direction.LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to InputMapper.Direction.RIGHT,
    )

    /**
     * Returns true if the key was handled (consumed), false otherwise.
     */
    fun onKeyDown(keyCode: Int): Boolean {
        val event = keyCodeToEvent(keyCode, pressed = true) ?: return false
        webRtcManager.sendDataChannelMessage(event.toJson())
        return true
    }

    fun onKeyUp(keyCode: Int): Boolean {
        if (keyCode in dpadToDirection) mapper.resetAcceleration()
        val event = keyCodeToEvent(keyCode, pressed = false) ?: return false
        webRtcManager.sendDataChannelMessage(event.toJson())
        return true
    }

    private fun keyCodeToEvent(keyCode: Int, pressed: Boolean): InputEvent? {
        dpadToDirection[keyCode]?.let { dir ->
            return if (pressed) mapper.moveCursor(dir) else null
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> if (pressed) InputEvent.Click("left") else null
            KeyEvent.KEYCODE_MENU  -> if (pressed) InputEvent.Click("right") else null
            else                   -> null
        }
    }
}
