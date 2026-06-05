package com.andrometa.pullout.download

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DownloadRecord): Long

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus)

    @Query("UPDATE downloads SET bytesDownloaded = :bytes, totalBytes = :total, status = :status WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, total: Long, status: DownloadStatus)

    @Query("UPDATE downloads SET muxPhase = :phase WHERE id = :id")
    suspend fun updateMuxPhase(id: Long, phase: String)

    @Query("UPDATE downloads SET mediaStoreUriString = :uri WHERE id = :id")
    suspend fun updateMediaStoreUri(id: Long, uri: String)

    @Query("UPDATE downloads SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("UPDATE downloads SET status = 'FAILED_NETWORK' WHERE status = 'DOWNLOADING'")
    suspend fun resetStuckDownloads()

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    fun getAllLive(): LiveData<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','DOWNLOADING','FAILED_NETWORK') ORDER BY timestamp DESC")
    fun getActiveLive(): LiveData<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadRecord?

    @Query("DELETE FROM downloads WHERE status IN ('COMPLETE','FAILED')")
    suspend fun clearHistory()
}
