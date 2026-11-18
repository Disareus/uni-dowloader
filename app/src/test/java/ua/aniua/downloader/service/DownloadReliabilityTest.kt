package ua.aniua.downloader.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.aniua.downloader.model.DownloadState
import ua.aniua.downloader.model.DownloadTask

class DownloadReliabilityTest {

    @Test
    fun testRecoverInterruptedTasksAfterProcessDeath() {
        val tasks = listOf(
            DownloadTask(id = "t1", mediaTitle = "Anime A", state = DownloadState.DOWNLOADING, speed = "2.5 MB/s", eta = "10s"),
            DownloadTask(id = "t2", mediaTitle = "Anime B", state = DownloadState.PENDING),
            DownloadTask(id = "t3", mediaTitle = "Anime C", state = DownloadState.COMPLETED, progress = 100f),
            DownloadTask(id = "t4", mediaTitle = "Anime D", state = DownloadState.ERROR, errorMessage = "403")
        )

        val recovered = DownloadQueueManager.recoverInterruptedTasks(tasks)
        assertEquals(4, recovered.size)

        assertEquals(DownloadState.CANCELLED, recovered[0].state)
        assertEquals("", recovered[0].speed)
        assertEquals("", recovered[0].eta)
        assertNotNull(recovered[0].errorMessage)

        assertEquals(DownloadState.CANCELLED, recovered[1].state)
        assertEquals(DownloadState.COMPLETED, recovered[2].state)
        assertEquals(DownloadState.ERROR, recovered[3].state)
        assertEquals("403", recovered[3].errorMessage)
    }

    @Test
    fun testSmartHttpRetryClassification() {
        assertTrue(HlsSegmentDownloader.isRetryableHttpCode(408))
        assertTrue(HlsSegmentDownloader.isRetryableHttpCode(429))
        assertTrue(HlsSegmentDownloader.isRetryableHttpCode(500))
        assertTrue(HlsSegmentDownloader.isRetryableHttpCode(502))
        assertTrue(HlsSegmentDownloader.isRetryableHttpCode(503))
        assertTrue(HlsSegmentDownloader.isRetryableHttpCode(504))

        assertFalse(HlsSegmentDownloader.isRetryableHttpCode(400))
        assertFalse(HlsSegmentDownloader.isRetryableHttpCode(401))
        assertFalse(HlsSegmentDownloader.isRetryableHttpCode(403))
        assertFalse(HlsSegmentDownloader.isRetryableHttpCode(404))
        assertFalse(HlsSegmentDownloader.isRetryableHttpCode(410))
    }

    @Test
    fun testExponentialBackoffWithJitterBounds() {
        val b1 = HlsSegmentDownloader.computeBackoffMs(1, baseMs = 200L, maxMs = 2000L)
        val b2 = HlsSegmentDownloader.computeBackoffMs(2, baseMs = 200L, maxMs = 2000L)
        val b5 = HlsSegmentDownloader.computeBackoffMs(5, baseMs = 200L, maxMs = 2000L)

        assertTrue("Attempt 1 backoff should be >= 200ms, got $b1", b1 in 200L..300L)
        assertTrue("Attempt 2 backoff should be >= 400ms, got $b2", b2 in 400L..600L)
        assertTrue("Attempt 5 backoff should be capped near 2000ms + jitter, got $b5", b5 in 2000L..2700L)
    }

    @Test
    fun testCancelControllerSetsFlag() {
        val controller = HlsSegmentDownloader.DownloadCancelController()
        assertFalse(controller.isCancelled())
        controller.cancelAll()
        assertTrue(controller.isCancelled())
    }
}
