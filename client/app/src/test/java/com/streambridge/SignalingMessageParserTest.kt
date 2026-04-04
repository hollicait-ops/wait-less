package com.streambridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingMessageParserTest {

    @Test
    fun `stream-info returns StreamInfo with video and input ports`() {
        val result = parseSignalingMessage("""{"type":"stream-info","videoPort":9000,"inputPort":9001}""")
        assertTrue(result is SignalingMessage.StreamInfo)
        val info = result as SignalingMessage.StreamInfo
        assertEquals(9000, info.videoPort)
        assertEquals(9001, info.inputPort)
    }

    @Test
    fun `stream-info uses defaults when ports are absent`() {
        val result = parseSignalingMessage("""{"type":"stream-info"}""")
        assertTrue(result is SignalingMessage.StreamInfo)
        val info = result as SignalingMessage.StreamInfo
        assertEquals(9000, info.videoPort)
        assertEquals(9001, info.inputPort)
    }

    @Test
    fun `unknown type returns null`() {
        assertNull(parseSignalingMessage("""{"type":"offer","sdp":"v=0"}"""))
    }

    @Test
    fun `missing type field returns null`() {
        assertNull(parseSignalingMessage("""{"videoPort":9000}"""))
    }

    @Test
    fun `empty type field returns null`() {
        assertNull(parseSignalingMessage("""{"type":""}"""))
    }

    @Test
    fun `invalid JSON returns null`() {
        assertNull(parseSignalingMessage("not json at all"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(parseSignalingMessage(""))
    }
}
