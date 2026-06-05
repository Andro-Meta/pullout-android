package com.andrometa.pullout.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.OutputStream

class MediaStoreWriter(private val context: Context) {
    data class OpenedFile(val uri: Uri, val stream: OutputStream)

    fun open(filename: String, mimeType: String): OpenedFile? {
        val safe = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safe)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/PULLOUT")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val stream = context.contentResolver.openOutputStream(uri) ?: run {
            context.contentResolver.delete(uri, null, null)
            return null
        }
        return OpenedFile(uri, stream)
    }

    fun finalize(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }

    fun delete(uri: Uri) = context.contentResolver.delete(uri, null, null)
}
