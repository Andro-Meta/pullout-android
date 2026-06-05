package com.andrometa.pullout.api

import com.andrometa.pullout.util.SettingsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CobaltApiClient(private val settings: SettingsRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * Submit a URL to the local cobalt API.
     * Always sends localProcessing:"forced" so the server never calls ffmpeg.
     */
    suspend fun submit(url: String): CobaltResponse {
        val body = JSONObject().apply {
            put("url", url)
            put("localProcessing", "forced")
            put("downloadMode", if (settings.audioOnlyMode) "audio" else "auto")
            put("videoQuality", settings.defaultQuality)
            put("filenameStyle", "pretty")
        }.toString()

        val request = Request.Builder()
            .url("${settings.cobaltInstanceUrl}/")
            .post(body.toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw IOException("Empty response body")
            parseResponse(responseBody)
        } catch (e: Exception) {
            CobaltResponse.CobaltError("api.unreachable", mapOf("message" to e.message))
        }
    }

    private fun parseResponse(json: String): CobaltResponse {
        return try {
            val obj = JSONObject(json)
            val status = obj.optString("status", "error")

            when (status) {
                "tunnel", "redirect" -> {
                    val url = obj.getString("url")
                    val filename = obj.optString("filename", generateFilename())
                    val mimeType = guessMimeType(filename)
                    if (status == "tunnel") CobaltResponse.Tunnel(url, filename, mimeType)
                    else CobaltResponse.Redirect(url, filename, mimeType)
                }
                "local-processing" -> {
                    val type = obj.optString("type", "merge")
                    val service = obj.optString("service", "unknown")
                    val tunnelArr = obj.optJSONArray("tunnel")
                    val tunnels = mutableListOf<String>()
                    if (tunnelArr != null) {
                        for (i in 0 until tunnelArr.length()) tunnels.add(tunnelArr.getString(i))
                    }
                    val outputObj = obj.optJSONObject("output")
                    val filename = outputObj?.optString("filename") ?: generateFilename()
                    val mimeType = outputObj?.optString("type") ?: guessMimeType(filename)
                    val isHls = obj.optBoolean("isHLS", false)
                    CobaltResponse.LocalProcessing(type, service, tunnels, filename, mimeType, isHls)
                }
                "picker" -> {
                    val pickerArr = obj.optJSONArray("picker")
                    val items = mutableListOf<PickerItem>()
                    if (pickerArr != null) {
                        for (i in 0 until pickerArr.length()) {
                            val item = pickerArr.getJSONObject(i)
                            items.add(PickerItem(
                                type = item.optString("type", "photo"),
                                url = item.getString("url"),
                                thumb = item.optString("thumb", null)
                            ))
                        }
                    }
                    CobaltResponse.Picker(
                        items = items,
                        backgroundAudio = obj.optString("audio", null),
                        backgroundAudioFilename = obj.optString("audioFilename", null)
                    )
                }
                else -> {
                    val errObj = obj.optJSONObject("error")
                    val code = errObj?.optString("code") ?: "error.unknown"
                    CobaltResponse.CobaltError(code, null)
                }
            }
        } catch (e: Exception) {
            CobaltResponse.CobaltError("api.parseError", mapOf("message" to e.message))
        }
    }

    private fun guessMimeType(filename: String): String = when {
        filename.endsWith(".mp4", true) -> "video/mp4"
        filename.endsWith(".webm", true) -> "video/webm"
        filename.endsWith(".mkv", true) -> "video/x-matroska"
        filename.endsWith(".mp3", true) -> "audio/mpeg"
        filename.endsWith(".ogg", true) -> "audio/ogg"
        filename.endsWith(".opus", true) -> "audio/opus"
        filename.endsWith(".wav", true) -> "audio/wav"
        filename.endsWith(".gif", true) -> "image/gif"
        filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
        filename.endsWith(".png", true) -> "image/png"
        else -> "video/mp4"
    }

    private fun generateFilename(): String = "pullout_${System.currentTimeMillis()}.mp4"
}
