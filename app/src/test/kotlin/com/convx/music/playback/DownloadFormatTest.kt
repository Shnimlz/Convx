package com.convx.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFormatTest {
    @Test fun newDownloadsUseSelectedFormat() {
        assertEquals(DownloadFormat.FLAC, downloadFormatForRequest(false, null, DownloadFormat.FLAC))
        assertEquals(DownloadFormat.STANDARD, downloadFormatForRequest(false, null, DownloadFormat.STANDARD))
    }

    @Test fun retriesKeepFlacAfterSettingsChange() {
        assertEquals(DownloadFormat.FLAC, downloadFormatForRequest(true, "audio/flac", DownloadFormat.STANDARD))
    }

    @Test fun legacyAndLossyPartialDownloadsCannotBecomeFlac() {
        assertEquals(DownloadFormat.STANDARD, downloadFormatForRequest(true, null, DownloadFormat.FLAC))
        assertEquals(DownloadFormat.STANDARD, downloadFormatForRequest(true, "audio/mp4", DownloadFormat.FLAC))
    }

    @Test fun recognizesNativeFlacInsteadOfTrustingProviderLabels() {
        assertTrue(isFlacHeader("fLaC".toByteArray()))
        assertTrue(isFlacHeader("fLaCmore data".toByteArray()))
        assertFalse(isFlacHeader("OggS".toByteArray()))
        assertFalse(isFlacHeader("ID3audio".toByteArray()))
        assertFalse(isFlacHeader("ftyp".toByteArray()))
        assertFalse(isFlacHeader("<html>".toByteArray()))
        assertFalse(isFlacHeader(byteArrayOf()))
        assertFalse(isFlacHeader("fLa".toByteArray()))
    }
}
