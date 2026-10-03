package com.convx.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadByteRangeTest {
    @Test
    fun `resuming a large song requests the remaining bytes`() {
        assertEquals("12000000-24999999", downloadByteRange(12_000_000, -1, 25_000_000))
    }

    @Test
    fun `unknown length is not capped at ten megabytes`() {
        assertEquals("0-", downloadByteRange(0, -1, null))
        assertEquals("12000000-", downloadByteRange(12_000_000, -1, null))
    }

    @Test
    fun `bounded requests use an inclusive end`() {
        assertEquals("100-199", downloadByteRange(100, 100, 1000))
        assertEquals("0-999", downloadByteRange(0, -1, 1000))
    }
}
