package com.streambridge

/**
 * WiFi frequency band.
 * No Android imports — fully testable on JVM.
 */
enum class WifiBand { BAND_2_4_GHZ, BAND_5_GHZ, UNKNOWN }

/**
 * Maps a WiFi frequency in MHz to a [WifiBand].
 * 2.4 GHz channels span 2400–2500 MHz; 5 GHz channels span 4900–5900 MHz.
 * Returns [WifiBand.UNKNOWN] when the frequency is outside both ranges
 * (e.g. not connected, or an unsupported 6 GHz band).
 */
fun wifiBandFromFrequency(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
    in 2400..2500 -> WifiBand.BAND_2_4_GHZ
    in 4900..5900 -> WifiBand.BAND_5_GHZ
    else          -> WifiBand.UNKNOWN
}
