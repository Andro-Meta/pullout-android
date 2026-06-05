package com.andrometa.pullout

import com.andrometa.pullout.api.CobaltResponse
import com.andrometa.pullout.api.PickerItem
import org.junit.Assert.*
import org.junit.Test

class CobaltApiClientParseTest {
    @Test fun tunnelDataClass() {
        val r = CobaltResponse.Tunnel("https://cdn.example.com/v.mp4", "video.mp4", "video/mp4")
        assertEquals("https://cdn.example.com/v.mp4", r.url)
        assertEquals("video.mp4", r.filename)
    }

    @Test fun localProcessingDataClass() {
        val r = CobaltResponse.LocalProcessing("merge", "youtube",
            listOf("https://v.example.com", "https://a.example.com"), "output.mp4", "video/mp4")
        assertEquals(2, r.tunnels.size)
        assertEquals("merge", r.type)
    }

    @Test fun pickerDataClass() {
        val r = CobaltResponse.Picker(
            listOf(PickerItem("photo", "https://example.com/img.jpg", null)),
            null, null)
        assertEquals(1, r.items.size)
    }

    @Test fun errorDataClass() {
        val r = CobaltResponse.CobaltError("error.link.invalid", null)
        assertEquals("error.link.invalid", r.code)
    }
}
