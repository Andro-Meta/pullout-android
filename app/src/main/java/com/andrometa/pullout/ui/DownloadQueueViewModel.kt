package com.andrometa.pullout.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.andrometa.pullout.download.DownloadRecord
import com.andrometa.pullout.download.DownloadRepository
import com.andrometa.pullout.download.DownloadService
import kotlinx.coroutines.launch

class DownloadQueueViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DownloadRepository(app)
    val allDownloads: LiveData<List<DownloadRecord>> = repo.allDownloads
    val activeDownloads: LiveData<List<DownloadRecord>> = repo.activeDownloads
    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }

    /** Cancels a queued/active download via the service (aborts work, deletes record). */
    fun cancelDownload(record: DownloadRecord) {
        DownloadService.cancel(getApplication(), record.id)
    }
}
