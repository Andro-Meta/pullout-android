package com.andrometa.pullout

import com.andrometa.pullout.download.DownloadRecord
import com.andrometa.pullout.download.DownloadStatus
import com.andrometa.pullout.download.StatusConverters
import org.junit.Assert.*
import org.junit.Test

class DownloadStatusTest {
    @Test fun defaultStatusIsQueued() = assertEquals(DownloadStatus.QUEUED, DownloadRecord().status)
    @Test fun statusEnumRoundTrip() {
        val c = StatusConverters()
        DownloadStatus.values().forEach { assertEquals(it, c.toStatus(c.fromStatus(it))) }
    }
    @Test fun allFiveStatuses() = assertEquals(5, DownloadStatus.values().size)
    @Test fun mediaStoreUriField() = assertEquals("", DownloadRecord().mediaStoreUriString)
    @Test fun isMuxedDefaultFalse() = assertFalse(DownloadRecord().isMuxed)
    @Test fun muxPhaseDefaultEmpty() = assertEquals("", DownloadRecord().muxPhase)
}
