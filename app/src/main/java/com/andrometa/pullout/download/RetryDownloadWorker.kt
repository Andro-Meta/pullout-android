package com.andrometa.pullout.download

import android.content.Context
import androidx.work.*
import com.andrometa.pullout.util.NotificationHelper
import java.util.concurrent.TimeUnit

class RetryDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val recordId = inputData.getLong(KEY_RECORD_ID, -1L)
        val originalUrl = inputData.getString(KEY_ORIGINAL_URL) ?: return Result.failure()
        val filename = inputData.getString(KEY_FILENAME) ?: ""
        NotificationHelper(applicationContext).showRetryReady(recordId, originalUrl, filename)
        return Result.success()
    }

    companion object {
        private const val KEY_RECORD_ID = "recordId"
        private const val KEY_ORIGINAL_URL = "originalUrl"
        private const val KEY_FILENAME = "filename"

        fun schedule(ctx: Context, recordId: Long, originalUrl: String, filename: String) {
            val data = workDataOf(KEY_RECORD_ID to recordId, KEY_ORIGINAL_URL to originalUrl, KEY_FILENAME to filename)
            val request = OneTimeWorkRequestBuilder<RetryDownloadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("retry_$recordId")
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "retry_$recordId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
