package com.streambridge

import org.junit.Assert.assertEquals
import org.junit.Test

class InputEventTest {

    @Test
    fun `MouseMove serialises correctly`() {
        val json = InputEvent.MouseMove(0.612f, 0.341f).toJson()
        assertEquals("mousemove", json.getString("type"))
        assertEquals(0.612, json.getDouble("x"), 0.001)
        assertEquals(0.341, json.getDouble("y"), 0.001)
    }

    @Test
    fun `Click left serialises correctly`() {
        val json = InputEvent.Click("left").toJson()
        assertEquals("click", json.getString("type"))
        assertEquals("left", json.getString("button"))
    }

    @Test
    fun `Click right serialises correctly`() {
        val json = InputEvent.Click("right").toJson()
        assertEquals("right", json.getString("button"))
    }

    @Test
    fun `Scroll serialises correctly`() {
        val json = InputEvent.Scroll(-3).toJson()
        assertEquals("scroll", json.getString("type"))
        assertEquals(-3, json.getInt("dy"))
    }

    @Test
    fun `KeyDown serialises correctly`() {
        val json = InputEvent.KeyDown("Return").toJson()
        assertEquals("keydown", json.getString("type"))
        assertEquals("Return", json.getString("key"))
    }

    @Test
    fun `KeyUp serialises correctly`() {
        val json = InputEvent.KeyUp("Return").toJson()
        assertEquals("keyup", json.getString("type"))
        assertEquals("Return", json.getString("key"))
    }

    @Test
    fun `MouseMove coordinates are preserved at boundary values`() {
        val topLeft = InputEvent.MouseMove(0f, 0f).toJson()
        assertEquals(0.0, topLeft.getDouble("x"), 0.0001)
        assertEquals(0.0, topLeft.getDouble("y"), 0.0001)

        val bottomRight = InputEvent.MouseMove(1f, 1f).toJson()
        assertEquals(1.0, bottomRight.getDouble("x"), 0.0001)
        assertEquals(1.0, bottomRight.getDouble("y"), 0.0001)
    }
}
