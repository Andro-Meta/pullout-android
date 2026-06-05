package com.andrometa.pullout.api

data class PickerItem(
    val type: String,  // "photo"|"video"|"gif"
    val url: String,
    val thumb: String?
)

sealed class CobaltResponse {
    data class Tunnel(val url: String, val filename: String, val mimeType: String) : CobaltResponse()
    data class Redirect(val url: String, val filename: String, val mimeType: String) : CobaltResponse()
    data class LocalProcessing(
        val type: String,       // "merge"|"mute"|"audio"|"gif"|"remux"
        val service: String,
        val tunnels: List<String>,
        val outputFilename: String,
        val outputMimeType: String,
        val isHls: Boolean = false
    ) : CobaltResponse()
    data class Picker(
        val items: List<PickerItem>,
        val backgroundAudio: String?,
        val backgroundAudioFilename: String?
    ) : CobaltResponse()
    data class CobaltError(val code: String, val context: Map<String, Any?>?) : CobaltResponse()
}
