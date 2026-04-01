package com.streambridge

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiBandTest {

    // --- 2.4 GHz ---

    @Test
    fun `channel 1 (2412 MHz) is 2_4 GHz`() {
        assertEquals(WifiBand.BAND_2_4_GHZ, wifiBandFromFrequency(2412))
    }

    @Test
    fun `channel 6 (2437 MHz) is 2_4 GHz`() {
        assertEquals(WifiBand.BAND_2_4_GHZ, wifiBandFromFrequency(2437))
    }

    @Test
    fun `channel 13 (2472 MHz) is 2_4 GHz`() {
        assertEquals(WifiBand.BAND_2_4_GHZ, wifiBandFromFrequency(2472))
    }

    // --- 5 GHz ---

    @Test
    fun `channel 36 (5180 MHz) is 5 GHz`() {
        assertEquals(WifiBand.BAND_5_GHZ, wifiBandFromFrequency(5180))
    }

    @Test
    fun `channel 100 (5500 MHz) is 5 GHz`() {
        assertEquals(WifiBand.BAND_5_GHZ, wifiBandFromFrequency(5500))
    }

    @Test
    fun `channel 165 (5825 MHz) is 5 GHz`() {
        assertEquals(WifiBand.BAND_5_GHZ, wifiBandFromFrequency(5825))
    }

    // --- Unknown / edge cases ---

    @Test
    fun `frequency 0 (not connected) returns UNKNOWN`() {
        assertEquals(WifiBand.UNKNOWN, wifiBandFromFrequency(0))
    }

    @Test
    fun `frequency -1 returns UNKNOWN`() {
        assertEquals(WifiBand.UNKNOWN, wifiBandFromFrequency(-1))
    }

    @Test
    fun `6 GHz frequency (5955 MHz) returns UNKNOWN`() {
        assertEquals(WifiBand.UNKNOWN, wifiBandFromFrequency(5955))
    }
}
