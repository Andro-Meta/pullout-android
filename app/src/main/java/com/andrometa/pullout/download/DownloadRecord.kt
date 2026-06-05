package com.andrometa.pullout.download

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETE, FAILED, FAILED_NETWORK }

class StatusConverters {
    @TypeConverter fun fromStatus(s: DownloadStatus): String = s.name
    @TypeConverter fun toStatus(s: String): DownloadStatus = DownloadStatus.valueOf(s)
}

@Entity(tableName = "downloads")
@TypeConverters(StatusConverters::class)
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalUrl: String = "",
    val cobaltUrl: String = "",
    val filename: String = "",
    val mimeType: String = "application/octet-stream",
    val cookies: String = "",
    val userAgent: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val mediaStoreUriString: String = "",
    val isMuxed: Boolean = false,
    val muxPhase: String = ""   // "DOWNLOADING_VIDEO"|"DOWNLOADING_AUDIO"|"MERGING"|""
)
