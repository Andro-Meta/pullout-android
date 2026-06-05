package com.andrometa.pullout.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.andrometa.pullout.api.CobaltResponse
import com.andrometa.pullout.util.NotificationHelper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val activeCount = AtomicInteger(0)
    private lateinit var repository: DownloadRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var mediaStoreWriter: MediaStoreWriter
    private lateinit var muxingManager: LocalMuxingManager

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository(this)
        notificationHelper = NotificationHelper(this)
        mediaStoreWriter = MediaStoreWriter(this)
        muxingManager = LocalMuxingManager(this)
        scope.launch { repository.resetStuckDownloads() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground MUST be called on main thread before any coroutines
        val notification = notificationHelper.buildForeground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.FOREGROUND_ID + 1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.FOREGROUND_ID + 1, notification)
        }

        when (intent?.action) {
            ACTION_DIRECT -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val record = DownloadRecord(
                    originalUrl = intent.getStringExtra(EXTRA_ORIGINAL_URL) ?: url,
                    cobaltUrl = url,
                    filename = intent.getStringExtra(EXTRA_FILENAME) ?: "pullout_download",
                    mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "video/mp4"
                )
                scope.launch {
                    val id = repository.insert(record)
                    processDirectDownload(record.copy(id = id))
                }
            }
            ACTION_MUX -> {
                val tunnels = intent.getStringArrayListExtra(EXTRA_TUNNELS) ?: return START_NOT_STICKY
                val type = intent.getStringExtra(EXTRA_TYPE) ?: "merge"
                val service = intent.getStringExtra(EXTRA_SERVICE) ?: "unknown"
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "pullout_mux"
                val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "video/mp4"
                val originalUrl = intent.getStringExtra(EXTRA_ORIGINAL_URL) ?: ""
                val response = CobaltResponse.LocalProcessing(type, service, tunnels, filename, mimeType)
                val record = DownloadRecord(originalUrl = originalUrl, filename = filename, mimeType = mimeType, isMuxed = true)
                scope.launch {
                    val id = repository.insert(record)
                    activeCount.incrementAndGet()
                    try {
                        muxingManager.process(id, response, repository, notificationHelper, mediaStoreWriter)
                    } finally {
                        if (activeCount.decrementAndGet() == 0) stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processDirectDownload(record: DownloadRecord) {
        activeCount.incrementAndGet()
        try {
            repository.updateStatus(record.id, DownloadStatus.DOWNLOADING)
            val req = Request.Builder().url(record.cobaltUrl).build()
            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty body")
                val contentLength = body.contentLength()
                val opened = mediaStoreWriter.open(record.filename, record.mimeType)
                    ?: throw IOException("MediaStore open failed")
                try {
                    opened.stream.use { out ->
                        val buffer = ByteArray(16 * 1024)
                        var totalRead = 0L
                        var lastUpdate = 0L
                        body.byteStream().use { inp ->
                            var n: Int
                            while (inp.read(buffer).also { n = it } != -1) {
                                out.write(buffer, 0, n)
                                totalRead += n
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 500) {
                                    lastUpdate = now
                                    repository.updateProgress(record.id, totalRead, contentLength)
                                    notificationHelper.updateProgress(record.id, totalRead.toInt(), contentLength.toInt())
                                }
                            }
                        }
                    }
                    mediaStoreWriter.finalize(opened.uri)
                    repository.updateMediaStoreUri(record.id, opened.uri.toString())
                    repository.updateStatus(record.id, DownloadStatus.COMPLETE)
                    notificationHelper.showComplete(record.id, record.filename, opened.uri, record.mimeType)
                } catch (e: Exception) {
                    mediaStoreWriter.delete(opened.uri)
                    throw e
                }
            }
        } catch (e: UnknownHostException) {
            handleNetworkFail(record)
        } catch (e: IOException) {
            if (e.message?.contains("ENOSPC") == true) {
                notificationHelper.showStorageFull()
                repository.updateStatus(record.id, DownloadStatus.FAILED)
            } else {
                handleNetworkFail(record)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            repository.updateStatus(record.id, DownloadStatus.FAILED)
            notificationHelper.showFailed(record.id, record.filename)
        } finally {
            if (activeCount.decrementAndGet() == 0) stopSelf()
        }
    }

    private suspend fun handleNetworkFail(record: DownloadRecord) {
        repository.updateStatus(record.id, DownloadStatus.FAILED_NETWORK)
        val current = repository.getById(record.id) ?: return
        if (current.retryCount < 3) {
            repository.incrementRetry(record.id)
            RetryDownloadWorker.schedule(this, record.id, record.originalUrl, record.filename)
        } else {
            notificationHelper.showFailed(record.id, record.filename)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_DIRECT = "com.andrometa.pullout.DIRECT"
        const val ACTION_MUX = "com.andrometa.pullout.MUX"
        const val EXTRA_URL = "url"
        const val EXTRA_ORIGINAL_URL = "originalUrl"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_MIME_TYPE = "mimeType"
        const val EXTRA_TUNNELS = "tunnels"
        const val EXTRA_TYPE = "type"
        const val EXTRA_SERVICE = "service"

        fun startDirect(ctx: Context, cobaltUrl: String, filename: String, mimeType: String, originalUrl: String) {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_DIRECT
                putExtra(EXTRA_URL, cobaltUrl)
                putExtra(EXTRA_ORIGINAL_URL, originalUrl)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_MIME_TYPE, mimeType)
            })
        }

        fun startMux(ctx: Context, response: CobaltResponse.LocalProcessing, originalUrl: String) {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_MUX
                putStringArrayListExtra(EXTRA_TUNNELS, ArrayList(response.tunnels))
                putExtra(EXTRA_TYPE, response.type)
                putExtra(EXTRA_SERVICE, response.service)
                putExtra(EXTRA_FILENAME, response.outputFilename)
                putExtra(EXTRA_MIME_TYPE, response.outputMimeType)
                putExtra(EXTRA_ORIGINAL_URL, originalUrl)
            })
        }
    }
}
