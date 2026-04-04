package com.streambridge

import android.view.KeyEvent

/**
 * Bridges Android KeyEvent codes to InputMapper and sends resulting
 * InputEvents over UDP via [UdpVideoReceiver].
 */
class InputHandler(private val receiver: UdpVideoReceiver) {

    private val mapper = InputMapper()

    private val dpadToDirection = mapOf(
        KeyEvent.KEYCODE_DPAD_UP    to InputMapper.Direction.UP,
        KeyEvent.KEYCODE_DPAD_DOWN  to InputMapper.Direction.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT  to InputMapper.Direction.LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to InputMapper.Direction.RIGHT,
    )

    fun onKeyDown(keyCode: Int): Boolean {
        val event = keyCodeToEvent(keyCode, pressed = true) ?: return false
        receiver.sendInputEvent(event.toJson())
        return true
    }

    fun onKeyUp(keyCode: Int): Boolean {
        if (keyCode in dpadToDirection) mapper.resetAcceleration()
        val event = keyCodeToEvent(keyCode, pressed = false) ?: return false
        receiver.sendInputEvent(event.toJson())
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
