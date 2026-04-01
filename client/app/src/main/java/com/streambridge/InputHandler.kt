package com.streambridge

import android.view.KeyEvent

/**
 * Bridges Android KeyEvent codes to InputMapper and sends resulting
 * InputEvents over the WebRTC DataChannel.
 */
class InputHandler(private val webRtcManager: WebRTCManager) {

    private val mapper = InputMapper()

    /**
     * Returns true if the key was handled (consumed), false otherwise.
     */
    fun onKeyDown(keyCode: Int): Boolean {
        val event = keyCodeToEvent(keyCode, pressed = true) ?: return false
        webRtcManager.sendDataChannelMessage(event.toJson())
        return true
    }

    fun onKeyUp(keyCode: Int): Boolean {
        mapper.resetAcceleration()
        val event = keyCodeToEvent(keyCode, pressed = false) ?: return false
        webRtcManager.sendDataChannelMessage(event.toJson())
        return true
    }

    private fun keyCodeToEvent(keyCode: Int, pressed: Boolean): InputEvent? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP        -> mapper.moveCursor(InputMapper.Direction.UP)
        KeyEvent.KEYCODE_DPAD_DOWN      -> mapper.moveCursor(InputMapper.Direction.DOWN)
        KeyEvent.KEYCODE_DPAD_LEFT      -> mapper.moveCursor(InputMapper.Direction.LEFT)
        KeyEvent.KEYCODE_DPAD_RIGHT     -> mapper.moveCursor(InputMapper.Direction.RIGHT)
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER          -> if (pressed) InputEvent.Click("left") else null
        KeyEvent.KEYCODE_MENU           -> if (pressed) InputEvent.Click("right") else null
        else                            -> null
    }
}
