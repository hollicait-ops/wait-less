package com.streambridge

/**
 * Pure cursor-movement and key-mapping logic — no Android imports.
 * Fully testable on JVM. InputHandler wraps this and bridges Android KeyEvent codes.
 */
class InputMapper {

    companion object {
        // Base cursor step per D-pad press (fraction of screen)
        const val BASE_STEP = 0.02f
        // Each consecutive keydown on the same direction multiplies speed up to this cap
        const val ACCELERATION_CAP = 4f
        const val ACCELERATION_STEP = 0.5f
    }

    // Cursor position, clamped to [0, 1]
    var cursorX: Float = 0.5f
        private set
    var cursorY: Float = 0.5f
        private set

    // Tracks consecutive presses in the same direction for acceleration
    private var lastDirection: Direction? = null
    private var consecutiveCount: Int = 0

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    /**
     * Move the cursor one step in [direction].
     * Returns a [InputEvent.MouseMove] with the new normalised position.
     */
    fun moveCursor(direction: Direction): InputEvent.MouseMove {
        if (direction == lastDirection) {
            consecutiveCount++
        } else {
            lastDirection = direction
            consecutiveCount = 1
        }

        val multiplier = (1f + ACCELERATION_STEP * (consecutiveCount - 1)).coerceAtMost(ACCELERATION_CAP)
        val step = BASE_STEP * multiplier

        when (direction) {
            Direction.UP    -> cursorY = (cursorY - step).coerceAtLeast(0f)
            Direction.DOWN  -> cursorY = (cursorY + step).coerceAtMost(1f)
            Direction.LEFT  -> cursorX = (cursorX - step).coerceAtLeast(0f)
            Direction.RIGHT -> cursorX = (cursorX + step).coerceAtMost(1f)
        }

        return InputEvent.MouseMove(cursorX, cursorY)
    }

    /** Reset acceleration state when a key is released. */
    fun resetAcceleration() {
        lastDirection = null
        consecutiveCount = 0
    }
}
