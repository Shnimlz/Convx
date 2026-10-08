package com.convx.music.playback

import com.convx.music.constants.AudioQuality
import com.convx.music.constants.SaavnAudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import com.music.jiosaavn.SaavnDownloadUrl
import com.music.jiosaavn.SaavnService
import org.junit.Test

class DataSaverPolicyTest {
    @Test fun missingLowBitrateMustNotFallBackTo320Kbps() {
        val highOnly = listOf(SaavnDownloadUrl("320kbps", "https://audio/high"))
        assertNull(SaavnService.selectBestUrl(highOnly, "96kbps", maxBitrateKbps = 96))
        assertEquals("https://audio/high", SaavnService.selectBestUrl(highOnly, "96kbps"))
        val choices = highOnly + SaavnDownloadUrl("48kbps", "https://audio/low")
        assertEquals("https://audio/low", SaavnService.selectBestUrl(choices, "96kbps", maxBitrateKbps = 96))
        assertNull(SaavnService.selectBestUrl(listOf(SaavnDownloadUrl("unknown", "https://audio/unknown")), "96kbps", 96))
    }

    @Test fun savingsOverrideBothProvidersWithoutChangingSavedChoices() {
        for (selected in AudioQuality.entries) {
            assertEquals(AudioQuality.LOW, effectiveAudioQuality(selected, true))
            assertEquals(selected, effectiveAudioQuality(selected, false))
        }
        for (selected in SaavnAudioQuality.entries) {
            assertEquals(SaavnAudioQuality.QUALITY_96, effectiveSaavnQuality(selected, true))
            assertEquals(selected, effectiveSaavnQuality(selected, false))
        }
    }

    @Test fun enablingSavingsWithLosslessEnabledUsesSeparateBytes() {
        val normal = playbackCacheKey("song", false, false)
        val lossless = playbackCacheKey("song", false, true)
        val saving = playbackCacheKey("song", true, true)
        assertNotEquals(normal, saving)
        assertNotEquals(lossless, saving)
        assertNotEquals(normal, lossless)
        assertEquals(saving, playbackCacheKey("song", true, false))
        assertEquals("song", normal) // Existing offline cache identity stays stable.
    }
}
