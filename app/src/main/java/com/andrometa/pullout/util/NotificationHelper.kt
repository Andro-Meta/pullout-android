package com.andrometa.pullout.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.andrometa.pullout.MainActivity
import com.andrometa.pullout.R

class NotificationHelper(private val context: Context) {
    private val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "pullout_downloads"
        const val FOREGROUND_ID = 1
        private const val BASE_ID = 1000
    }

    fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID,
            context.getString(R.string.server_channel),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) }
        mgr.createNotificationChannel(ch)
    }

    fun buildForeground(): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(context.getString(R.string.server_running))
        .setOngoing(true).setSilent(true).build()

    fun buildServerNotification(): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_upload)
        .setContentTitle(context.getString(R.string.server_running))
        .setOngoing(true).setSilent(true).build()

    fun updateProgress(recordId: Long, bytes: Int, total: Int) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notif_downloading))
            .setProgress(total.coerceAtLeast(1), bytes, total <= 0)
            .setOngoing(true).setSilent(true).build()
        mgr.notify((BASE_ID + recordId).toInt(), n)
    }

    fun showComplete(recordId: Long, filename: String, uri: Uri, mimeType: String) {
        val open = PendingIntent.getActivity(context, recordId.toInt(),
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(filename).setContentText(context.getString(R.string.notif_complete))
            .setAutoCancel(true).addAction(0, context.getString(R.string.action_open), open).build()
        mgr.notify((BASE_ID + recordId).toInt(), n)
    }

    fun showFailed(recordId: Long, filename: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(filename).setContentText(context.getString(R.string.notif_failed))
            .setAutoCancel(true).build()
        mgr.notify((BASE_ID + recordId).toInt(), n)
    }

    fun showStorageFull() {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_storage_full))
            .setOngoing(true).build()
        mgr.notify(BASE_ID - 1, n)
    }

    fun showRetryReady(recordId: Long, originalUrl: String, filename: String) {
        val tap = PendingIntent.getActivity(context, recordId.toInt(),
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND; type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, originalUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notif_retry_ready))
            .setContentText(filename.ifBlank { originalUrl })
            .setContentIntent(tap).setAutoCancel(true).build()
        mgr.notify((BASE_ID + recordId).toInt(), n)
    }

    fun cancel(recordId: Long) = mgr.cancel((BASE_ID + recordId).toInt())
}
