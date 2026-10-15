package com.maxrave.simpmusic.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifySyncPolicyTest {
    @Test
    fun dataSaverBlocksScheduledRefreshes() {
        assertFalse(
            SpotifySyncPolicy.shouldRefresh(
                lastSuccessfulSyncMs = 1_000L,
                nowMs = 10_000_000L,
                intervalMs = 1_000L,
                dataSaverEnabled = true,
            ),
        )
    }

    @Test
    fun missingSyncTimestampRequestsInitialRefresh() {
        assertTrue(
            SpotifySyncPolicy.shouldRefresh(
                lastSuccessfulSyncMs = null,
                nowMs = 10_000L,
                intervalMs = 60_000L,
                dataSaverEnabled = false,
            ),
        )
    }

    @Test
    fun dataSaverStillAllowsInitialCacheSeed() {
        assertTrue(
            SpotifySyncPolicy.shouldRefresh(
                lastSuccessfulSyncMs = null,
                nowMs = 10_000L,
                intervalMs = 60_000L,
                dataSaverEnabled = true,
            ),
        )
    }

    @Test
    fun dataSaverBacksOffAfterFailedInitialAttempt() {
        assertFalse(
            SpotifySyncPolicy.shouldRefresh(
                lastSuccessfulSyncMs = null,
                nowMs = 20_000L,
                intervalMs = 60_000L,
                dataSaverEnabled = true,
                lastAttemptMs = 10_000L,
            ),
        )
    }

    @Test
    fun dataSaverRetriesInitialAttemptAfterBackoff() {
        assertTrue(
            SpotifySyncPolicy.shouldRefresh(
                lastSuccessfulSyncMs = null,
                nowMs = SpotifySyncPolicy.INITIAL_SYNC_RETRY_BACKOFF_MS + 10_000L,
                intervalMs = 60_000L,
                dataSaverEnabled = true,
                lastAttemptMs = 10_000L,
            ),
        )
    }

    @Test
    fun recentSyncDoesNotRefreshBeforeInterval() {
        assertFalse(
            SpotifySyncPolicy.shouldRefresh(
                lastSuccessfulSyncMs = 10_000L,
                nowMs = 20_000L,
                intervalMs = 60_000L,
                dataSaverEnabled = false,
            ),
        )
    }

    @Test
    fun forcedRefreshOverridesDataSaver() {
        assertTrue(
            SpotifySyncPolicy.shouldRefresh(
                lastSuccessfulSyncMs = 10_000L,
                nowMs = 20_000L,
                intervalMs = 60_000L,
                dataSaverEnabled = true,
                forceRefresh = true,
            ),
        )
    }

    @Test
    fun failedSyncDoesNotAdvanceTimestamp() {
        assertEquals(
            1_000L,
            SpotifySyncPolicy.timestampAfterSync(
                previousSuccessfulSyncMs = 1_000L,
                nowMs = 10_000L,
                completedSuccessfully = false,
            ),
        )
    }
}
