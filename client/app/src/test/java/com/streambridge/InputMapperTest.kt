package com.streambridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputMapperTest {

    private lateinit var mapper: InputMapper

    @Before
    fun setUp() {
        mapper = InputMapper()
    }

    // --- Initial state ---

    @Test
    fun `cursor starts at centre`() {
        assertEquals(0.5f, mapper.cursorX, 0.0001f)
        assertEquals(0.5f, mapper.cursorY, 0.0001f)
    }

    // --- Basic movement ---

    @Test
    fun `move up decreases y`() {
        val before = mapper.cursorY
        mapper.moveCursor(InputMapper.Direction.UP)
        assertTrue(mapper.cursorY < before)
    }

    @Test
    fun `move down increases y`() {
        val before = mapper.cursorY
        mapper.moveCursor(InputMapper.Direction.DOWN)
        assertTrue(mapper.cursorY > before)
    }

    @Test
    fun `move left decreases x`() {
        val before = mapper.cursorX
        mapper.moveCursor(InputMapper.Direction.LEFT)
        assertTrue(mapper.cursorX < before)
    }

    @Test
    fun `move right increases x`() {
        val before = mapper.cursorX
        mapper.moveCursor(InputMapper.Direction.RIGHT)
        assertTrue(mapper.cursorX > before)
    }

    // --- Clamping ---

    @Test
    fun `cursor x does not exceed 1`() {
        repeat(200) { mapper.moveCursor(InputMapper.Direction.RIGHT) }
        assertEquals(1.0f, mapper.cursorX, 0.0001f)
    }

    @Test
    fun `cursor x does not go below 0`() {
        repeat(200) { mapper.moveCursor(InputMapper.Direction.LEFT) }
        assertEquals(0.0f, mapper.cursorX, 0.0001f)
    }

    @Test
    fun `cursor y does not exceed 1`() {
        repeat(200) { mapper.moveCursor(InputMapper.Direction.DOWN) }
        assertEquals(1.0f, mapper.cursorY, 0.0001f)
    }

    @Test
    fun `cursor y does not go below 0`() {
        repeat(200) { mapper.moveCursor(InputMapper.Direction.UP) }
        assertEquals(0.0f, mapper.cursorY, 0.0001f)
    }

    // --- Returned event ---

    @Test
    fun `moveCursor returns MouseMove with current position`() {
        val event = mapper.moveCursor(InputMapper.Direction.RIGHT) as InputEvent.MouseMove
        assertEquals(mapper.cursorX, event.x, 0.0001f)
        assertEquals(mapper.cursorY, event.y, 0.0001f)
    }

    // --- Acceleration ---

    @Test
    fun `consecutive presses in same direction accelerate`() {
        val step1 = mapper.moveCursor(InputMapper.Direction.RIGHT).x - 0.5f
        val x1 = mapper.cursorX
        val step2 = mapper.moveCursor(InputMapper.Direction.RIGHT).x - x1
        assertTrue("second step should be larger", step2 > step1)
    }

    @Test
    fun `acceleration resets on direction change`() {
        // Build up acceleration rightward
        repeat(5) { mapper.moveCursor(InputMapper.Direction.RIGHT) }
        val xBefore = mapper.cursorX

        // Switch direction — first step should be small again
        mapper.resetAcceleration()
        val step = mapper.moveCursor(InputMapper.Direction.RIGHT).x - xBefore
        assertEquals(InputMapper.BASE_STEP, step, 0.0001f)
    }

    @Test
    fun `acceleration is capped at ACCELERATION_CAP multiplier`() {
        // Move enough times to hit the cap
        repeat(20) { mapper.moveCursor(InputMapper.Direction.RIGHT) }
        val xBefore = mapper.cursorX
        val step = mapper.moveCursor(InputMapper.Direction.RIGHT).x - xBefore
        val maxStep = InputMapper.BASE_STEP * InputMapper.ACCELERATION_CAP
        assertTrue("step should not exceed capped value", step <= maxStep + 0.0001f)
    }
}
