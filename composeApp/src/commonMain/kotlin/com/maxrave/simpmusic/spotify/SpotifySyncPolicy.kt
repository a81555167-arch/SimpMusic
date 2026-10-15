package com.maxrave.simpmusic.spotify

/**
 * Shared policy for Spotify library synchronization.
 *
 * This policy deliberately separates local-first reads from remote refreshes:
 * cached data should be rendered immediately, while refresh decisions are made
 * by the caller. It does not perform network requests itself.
 */
object SpotifySyncPolicy {
    /** Minimum interval between ordinary library refreshes (6 hours). */
    const val DEFAULT_LIBRARY_REFRESH_INTERVAL_MS: Long = 6 * 60 * 60 * 1000L

    /** Home feed changes more often than a saved library, but should not be polled constantly. */
    const val DEFAULT_HOME_REFRESH_INTERVAL_MS: Long = 60 * 60 * 1000L

    /** Data saver blocks repeat refreshes; initial sync and manual refreshes remain available. */
    fun shouldRefresh(
        lastSuccessfulSyncMs: Long?,
        nowMs: Long,
        intervalMs: Long,
        dataSaverEnabled: Boolean,
        forceRefresh: Boolean = false,
    ): Boolean {
        if (forceRefresh) return true
        // First sync is required to seed the local cache. Data saver blocks repeat refreshes,
        // not the initial fetch needed to make local-first mode useful.
        if (lastSuccessfulSyncMs == null || lastSuccessfulSyncMs <= 0L) return true
        if (dataSaverEnabled) return false
        if (intervalMs <= 0L) return true
        if (nowMs < lastSuccessfulSyncMs) return false
        return nowMs - lastSuccessfulSyncMs >= intervalMs
    }

    /**
     * A failed or partial request must not advance the successful-sync marker,
     * otherwise stale local data could be treated as fresh indefinitely.
     */
    fun timestampAfterSync(
        previousSuccessfulSyncMs: Long?,
        nowMs: Long,
        completedSuccessfully: Boolean,
    ): Long? = if (completedSuccessfully) nowMs else previousSuccessfulSyncMs
}
