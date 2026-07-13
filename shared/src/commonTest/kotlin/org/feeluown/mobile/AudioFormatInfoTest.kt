package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioFormatInfoTest {
    @Test
    fun displaysKnownBitrateModesAndAverageBitrate() {
        assertEquals("CBR（固定比特率）", AudioBitrateMode.Cbr.displayName())
        assertEquals("VBR（可变比特率）", AudioBitrateMode.Vbr.displayName())
        assertEquals("ABR（平均比特率）", AudioBitrateMode.Abr.displayName())
        assertEquals("320 kbps", formatAudioBitrate(320_000))
    }

    @Test
    fun displaysUnavailableForMissingBitrate() {
        assertEquals("暂未提供", AudioBitrateMode.Unknown.displayName())
        assertEquals("暂未提供", formatAudioBitrate(null))
        assertEquals("暂未提供", formatAudioBitrate(0))
    }
}
