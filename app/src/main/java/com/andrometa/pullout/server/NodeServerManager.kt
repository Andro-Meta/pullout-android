package com.andrometa.pullout.server

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.janeasystems.nodejsmobile.NodeJsMobile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object NodeServerManager {
    private const val TAG = "NodeServerManager"
    private const val PORT = 9000
    private const val HEALTH_INTERVAL_MS = 500L
    private const val HEALTH_TIMEOUT_MS = 30_000L

    private val _serverState = MutableLiveData<ServerState>(ServerState.Cold)
    val serverState: LiveData<ServerState> = _serverState

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null
    private var nodeStarted = false

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    fun startServer(context: Context) {
        if (nodeStarted) {
            // Node is already running — just start health monitoring if needed
            startHealthMonitor()
            return
        }
        _serverState.postValue(ServerState.Warming(1))
        managerScope.launch {
            try {
                val projectDir = prepareNodeProject(context)
                Log.i(TAG, "Starting Node.js from $projectDir")

                // NodeJsMobile.startNodeWithArguments is an instance method that BLOCKS
                // until Node exits, so we run it on a dedicated thread.
                // We pass the full absolute path to main.js so Node can find it and
                // resolve node_modules relative to its directory.
                val mainJsPath = "$projectDir/main.js"
                val nodeInstance = NodeJsMobile()
                nodeStarted = true
                startHealthMonitor()

                // This blocks until Node exits (runs on IO thread pool)
                val exitCode = nodeInstance.startNodeWithArguments(
                    arrayOf("node", mainJsPath),
                    true  // redirectOutputToLogcat
                )
                Log.w(TAG, "Node.js exited with code $exitCode")
                nodeStarted = false
                _serverState.postValue(ServerState.Error("Server exited (code $exitCode)"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Node.js", e)
                nodeStarted = false
                _serverState.postValue(ServerState.Error("Failed to start: ${e.message}"))
            }
        }
    }

    /**
     * Copies nodejs-project from app assets to filesDir on first launch,
     * or if the version has changed.
     * Returns the absolute path to the project directory.
     */
    private fun prepareNodeProject(context: Context): String {
        val targetDir = File(context.filesDir, "nodejs-project")
        val versionFile = File(targetDir, "pullout-version.txt")
        val assetVersionStream = runCatching {
            context.assets.open("nodejs-project/pullout-version.txt")
        }.getOrNull()
        val assetVersion = assetVersionStream?.bufferedReader()?.readText()?.trim() ?: "unknown"
        assetVersionStream?.close()

        val installedVersion = if (versionFile.exists()) versionFile.readText().trim() else ""
        if (installedVersion == assetVersion && targetDir.exists()) {
            Log.i(TAG, "Node project up-to-date at version $assetVersion")
            return targetDir.absolutePath
        }

        Log.i(TAG, "Extracting nodejs-project (v$assetVersion) to ${targetDir.absolutePath}")
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()
        copyAssetDir(context, "nodejs-project", targetDir)
        Log.i(TAG, "Extraction complete")
        return targetDir.absolutePath
    }

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        for (name in assets) {
            val childAsset = "$assetPath/$name"
            val childDest = File(destDir, name)
            val subAssets = context.assets.list(childAsset)
            if (subAssets != null && subAssets.isNotEmpty()) {
                childDest.mkdirs()
                copyAssetDir(context, childAsset, childDest)
            } else {
                context.assets.open(childAsset).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun startHealthMonitor() {
        healthJob?.cancel()
        healthJob = managerScope.launch {
            val deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS
            var attempt = 0
            while (System.currentTimeMillis() < deadline) {
                attempt++
                _serverState.postValue(ServerState.Warming(attempt))
                if (isServerHealthy()) {
                    Log.i(TAG, "Server healthy after $attempt polls")
                    _serverState.postValue(ServerState.Ready)
                    // Keep monitoring in background for crashes
                    monitorLoop()
                    return@launch
                }
                delay(HEALTH_INTERVAL_MS)
            }
            _serverState.postValue(ServerState.Error("Server did not start within 30s"))
        }
    }

    private suspend fun monitorLoop() {
        while (true) {
            delay(5000)
            if (!isServerHealthy()) {
                _serverState.postValue(ServerState.Error("Server became unresponsive"))
                return
            }
        }
    }

    private fun isServerHealthy(): Boolean {
        return try {
            val req = Request.Builder().url("http://localhost:$PORT/").get().build()
            val resp = healthClient.newCall(req).execute()
            val ok = resp.code in 200..299 || resp.code == 405  // 405 = method not allowed = server up
            resp.close()
            ok
        } catch (e: Exception) { false }
    }

    fun isReady(): Boolean = _serverState.value is ServerState.Ready
}
