package com.andrometa.pullout.download

import android.content.Context
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadRepository(context: Context) {
    private val dao = DownloadDatabase.getInstance(context).downloadDao()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    val allDownloads: LiveData<List<DownloadRecord>> = dao.getAllLive()
    val activeDownloads: LiveData<List<DownloadRecord>> = dao.getActiveLive()

    suspend fun insert(record: DownloadRecord): Long = dao.insert(record)
    suspend fun updateStatus(id: Long, status: DownloadStatus) = dao.updateStatus(id, status)
    suspend fun updateProgress(id: Long, bytes: Long, total: Long) =
        dao.updateProgress(id, bytes, total, DownloadStatus.DOWNLOADING)
    suspend fun updateMuxPhase(id: Long, phase: String) = dao.updateMuxPhase(id, phase)
    suspend fun updateMediaStoreUri(id: Long, uri: String) = dao.updateMediaStoreUri(id, uri)
    suspend fun incrementRetry(id: Long) = dao.incrementRetry(id)
    suspend fun resetStuckDownloads() = dao.resetStuckDownloads()
    suspend fun getById(id: Long): DownloadRecord? = dao.getById(id)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun clearHistory() = dao.clearHistory()
    fun updateStatusAsync(id: Long, status: DownloadStatus) =
        ioScope.launch { dao.updateStatus(id, status) }
}
