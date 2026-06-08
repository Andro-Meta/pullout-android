package com.andrometa.pullout.download

import android.content.Context
import android.util.Log
import com.andrometa.pullout.api.CobaltResponse
import com.andrometa.pullout.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Handles local muxing of video+audio streams using the ffmpeg native binary
 * bundled via io.github.yearsyan:ffmpeg-mini.
 *
 * ffmpeg-mini ships libffmpegexe.so — a shared-library wrapper around the
 * ffmpeg CLI entry point. Android extracts it to the app's nativeLibraryDir
 * at install time. We invoke it via ProcessBuilder with a shell wrapper.
 *
 * On Android 10+ the .so in nativeLibraryDir is executable; on older versions
 * we fall back to copying it to filesDir first.
 */
class LocalMuxingManager(private val context: Context) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads two streams then muxes them. Updates muxPhase throughout.
     * All temp files are deleted in the outer finally block.
     * Returns the MediaStore URI string on success, null on failure.
     */
    suspend fun process(
        recordId: Long,
        response: CobaltResponse.LocalProcessing,
        repo: DownloadRepository,
        notificationHelper: NotificationHelper,
        mediaStoreWriter: MediaStoreWriter
    ): String? = withContext(Dispatchers.IO) {
        val videoTmp = File(context.cacheDir, "pullout_v_$recordId.tmp")
        val audioTmp = File(context.cacheDir, "pullout_a_$recordId.tmp")
        // The output temp file MUST carry the real container extension (.mp4/.webm/...)
        // — ffmpeg picks the muxer from the filename extension, and a ".tmp" output
        // makes it fail with "Unable to choose an output format".
        val outExt = response.outputFilename.substringAfterLast('.', "mp4").lowercase()
            .ifBlank { "mp4" }
        val outputTmp = File(context.cacheDir, "pullout_out_$recordId.$outExt")

        try {
            // Phase 1: Download video stream
            if (response.tunnels.isNotEmpty()) {
                repo.updateMuxPhase(recordId, "DOWNLOADING_VIDEO")
                downloadToFile(response.tunnels[0], videoTmp, recordId, repo, notificationHelper)
            }

            // Fast path: "proxy" type means cobalt is just proxying the bytes as-is
            // (e.g. TikTok). No ffmpeg processing is needed — the single tunnel IS
            // the final file. Use the downloaded video tmp directly as the output.
            val needsFfmpeg = response.type != "proxy"

            if (!needsFfmpeg) {
                // Skip ffmpeg entirely; videoTmp is the finished file.
                videoTmp.copyTo(outputTmp, overwrite = true)
            } else {
                // Phase 2: Download audio stream (if merge type has 2 tunnels)
                if (response.tunnels.size > 1 && response.type != "mute") {
                    repo.updateMuxPhase(recordId, "DOWNLOADING_AUDIO")
                    downloadToFile(response.tunnels[1], audioTmp, recordId, repo, notificationHelper)
                }

                // Phase 3: Mux
                repo.updateMuxPhase(recordId, "MERGING")
                val muxResult = mux(videoTmp, if (audioTmp.exists()) audioTmp else null, outputTmp, response.type)
                if (!muxResult) {
                    Log.e(TAG, "Mux failed for record $recordId")
                    return@withContext null
                }
            }

            // Write to MediaStore
            val opened = mediaStoreWriter.open(response.outputFilename, response.outputMimeType)
                ?: return@withContext null
            try {
                opened.stream.use { out ->
                    outputTmp.inputStream().use { inp -> inp.copyTo(out) }
                }
                mediaStoreWriter.finalize(opened.uri)
                val uriStr = opened.uri.toString()
                repo.updateMediaStoreUri(recordId, uriStr)
                repo.updateStatus(recordId, DownloadStatus.COMPLETE)
                repo.updateMuxPhase(recordId, "")
                notificationHelper.showComplete(recordId, response.outputFilename, opened.uri, response.outputMimeType)
                return@withContext uriStr
            } catch (e: Exception) {
                mediaStoreWriter.delete(opened.uri)
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "LocalMuxing failed", e)
            repo.updateStatus(recordId, DownloadStatus.FAILED)
            repo.updateMuxPhase(recordId, "")
            notificationHelper.showFailed(recordId, response.outputFilename)
            return@withContext null
        } finally {
            // Always clean up temp files — even if open() failed
            videoTmp.delete()
            audioTmp.delete()
            outputTmp.delete()
        }
    }

    private suspend fun downloadToFile(
        url: String, dest: File, recordId: Long,
        repo: DownloadRepository, notificationHelper: NotificationHelper
    ) {
        val req = Request.Builder().url(url).build()
        okHttpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} downloading stream")
            val body = response.body ?: throw java.io.IOException("Empty stream body")
            val contentLength = body.contentLength()
            dest.outputStream().use { out ->
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
                            repo.updateProgress(recordId, totalRead, contentLength)
                            notificationHelper.updateProgress(recordId, totalRead.toInt(), contentLength.toInt())
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs ffmpeg via the bundled libffmpegexe.so native binary.
     *
     * Android extracts shared libs to nativeLibraryDir at install time.
     * libffmpegexe.so is the ffmpeg CLI compiled as a shared library with
     * an exported `main` symbol — it can be executed directly on Android.
     */
    private fun mux(video: File, audio: File?, output: File, type: String): Boolean {
        val ffmpegBin = findFfmpegBinary() ?: run {
            Log.e(TAG, "ffmpeg binary not found in nativeLibraryDir")
            return false
        }

        val args: List<String> = buildList {
            add(ffmpegBin.absolutePath)
            add("-y")
            add("-i"); add(video.absolutePath)
            if (audio != null && type == "merge") {
                add("-i"); add(audio.absolutePath)
                add("-c:v"); add("copy")
                add("-c:a"); add("copy")
                add("-movflags"); add("+faststart")
            } else when (type) {
                "audio" -> { add("-vn"); add("-c:a"); add("copy") }
                "mute"  -> { add("-an"); add("-c:v"); add("copy") }
                else    -> { add("-c"); add("copy") }
            }
            add(output.absolutePath)
        }

        return try {
            Log.d(TAG, "ffmpeg cmd: ${args.joinToString(" ")}")
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            // libffmpegexe.so dynamically links libffmpeg.so at runtime; the linker
            // only searches LD_LIBRARY_PATH for app-private dirs, so point it at the
            // native lib dir or the executable cannot be linked.
            pb.environment()["LD_LIBRARY_PATH"] =
                "$nativeLibDir:" + (System.getenv("LD_LIBRARY_PATH") ?: "")
            val process = pb.start()

            // Drain output to logcat
            val outputReader = process.inputStream.bufferedReader()
            val logThread = Thread {
                try {
                    outputReader.lines().forEach { line ->
                        Log.d(TAG, "ffmpeg: $line")
                    }
                } catch (_: Exception) {}
            }
            logThread.isDaemon = true
            logThread.start()

            val exitCode = process.waitFor()
            logThread.join(2000)
            Log.i(TAG, "ffmpeg exited with code $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "ProcessBuilder failed", e)
            false
        }
    }

    /**
     * Finds the ffmpeg executable. On Android, nativeLibraryDir contains
     * libffmpegexe.so which is executable on API 26+ (minSdk of this project).
     *
     * Returns null cleanly if the AAR is not present (wrong ABI or missing
     * dependency) so the mux step can be marked FAILED without a crash.
     */
    private fun findFfmpegBinary(): File? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val libFile = File(nativeLibDir, "libffmpegexe.so")

        // Primary: use the native lib directly (preferred on Android 10+)
        if (libFile.exists() && libFile.canExecute()) return libFile

        // ffmpeg-mini AAR not in APK or wrong ABI — muxing unavailable
        if (!libFile.exists()) return null

        // Fallback: copy to filesDir (needed on some older devices where nativeLibraryDir is noexec)
        val execCopy = File(context.filesDir, "ffmpeg")
        return try {
            // Only copy if sizes differ (different version) or copy doesn't exist
            if (!execCopy.exists() || execCopy.length() != libFile.length()) {
                libFile.copyTo(execCopy, overwrite = true)
                execCopy.setExecutable(true, false)
            }
            if (execCopy.exists() && execCopy.length() > 0) execCopy else null
        } catch (e: Exception) {
            null  // clean failure — mux will be marked FAILED
        }
    }

    companion object {
        private const val TAG = "LocalMuxingManager"
    }
}
