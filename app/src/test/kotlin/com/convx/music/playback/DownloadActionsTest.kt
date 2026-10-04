package com.convx.music.playback

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * These pin the two asymmetries that the old inlined `songs.forEach` loops got wrong.
 * Both are about COMPLETED being treated differently from everything else, which is
 * exactly what a "simplify this when()" refactor would flatten back out.
 */
class DownloadActionsTest {

    @Test
    fun `a partially failed batch still offers cancellation while work is active`() {
        assertEquals(Download.STATE_DOWNLOADING, playlistDownloadState(listOf(
            Download.STATE_COMPLETED, Download.STATE_FAILED, Download.STATE_DOWNLOADING, null,
        )))
        assertEquals(Download.STATE_DOWNLOADING, playlistDownloadState(listOf(Download.STATE_RESTARTING)))
    }

    @Test
    fun `empty and failed playlists offer download while completed playlists offer removal`() {
        assertEquals(Download.STATE_STOPPED, playlistDownloadState(emptyList()))
        assertEquals(Download.STATE_STOPPED, playlistDownloadState(listOf(null, Download.STATE_FAILED)))
        assertEquals(Download.STATE_COMPLETED, playlistDownloadState(listOf(Download.STATE_COMPLETED)))
    }

    @Test
    fun `a song we have never downloaded is queued`() {
        assertTrue(shouldQueueDownload(null))
    }

    @Test
    fun `a failed or stopped download is requeued - this is the retry after a network drop`() {
        assertTrue(shouldQueueDownload(Download.STATE_FAILED))
        assertTrue(shouldQueueDownload(Download.STATE_STOPPED))
    }

    @Test
    fun `a completed download is never requeued`() {
        // The bug: DownloadManager#addDownload moves a COMPLETED download back to QUEUED,
        // so re-tapping download after a partial failure refetched the finished songs too.
        assertFalse(shouldQueueDownload(Download.STATE_COMPLETED))
    }

    @Test
    fun `work already scheduled is not queued a second time`() {
        assertFalse(shouldQueueDownload(Download.STATE_QUEUED))
        assertFalse(shouldQueueDownload(Download.STATE_DOWNLOADING))
        assertFalse(shouldQueueDownload(Download.STATE_RESTARTING))
    }

    @Test
    fun `cancel removes pending and in-flight downloads`() {
        assertTrue(shouldCancelDownload(Download.STATE_QUEUED))
        assertTrue(shouldCancelDownload(Download.STATE_DOWNLOADING))
        assertTrue(shouldCancelDownload(Download.STATE_RESTARTING))
        assertTrue(shouldCancelDownload(Download.STATE_FAILED))
        assertTrue(shouldCancelDownload(Download.STATE_STOPPED))
    }

    @Test
    fun `cancel never deletes a finished download`() {
        // The bug: tapping the "Downloading" row cancelled the whole list, wiping every
        // song that had already finished.
        assertFalse(shouldCancelDownload(Download.STATE_COMPLETED))
    }

    @Test
    fun `cancel ignores songs that were never downloaded`() {
        assertFalse(shouldCancelDownload(null))
    }
}
