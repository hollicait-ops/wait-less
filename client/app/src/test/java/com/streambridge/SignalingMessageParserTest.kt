package com.streambridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingMessageParserTest {

    // --- Happy paths ---

    @Test
    fun `offer returns Offer with the full JSON`() {
        val result = parseSignalingMessage("""{"type":"offer","sdp":"v=0 o=test"}""")
        assertTrue(result is SignalingMessage.Offer)
        assertEquals("offer", (result as SignalingMessage.Offer).json.getString("type"))
        assertEquals("v=0 o=test", result.json.getString("sdp"))
    }

    @Test
    fun `answer returns Answer with the full JSON`() {
        val result = parseSignalingMessage("""{"type":"answer","sdp":"v=0"}""")
        assertTrue(result is SignalingMessage.Answer)
        assertEquals("answer", (result as SignalingMessage.Answer).json.getString("type"))
    }

    @Test
    fun `ice-candidate returns IceCandidate with the full JSON`() {
        val result = parseSignalingMessage(
            """{"type":"ice-candidate","candidate":{"candidate":"candidate:1 1 UDP 123 192.168.1.1 5000 typ host"}}"""
        )
        assertTrue(result is SignalingMessage.IceCandidate)
        val candidate = (result as SignalingMessage.IceCandidate).json.getJSONObject("candidate")
        assertTrue(candidate.getString("candidate").startsWith("candidate:"))
    }

    // --- Null / unknown cases ---

    @Test
    fun `unknown type returns null`() {
        assertNull(parseSignalingMessage("""{"type":"ping"}"""))
    }

    @Test
    fun `missing type field returns null`() {
        assertNull(parseSignalingMessage("""{"sdp":"v=0"}"""))
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
