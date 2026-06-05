package com.andrometa.pullout.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.ArrayAdapter
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.activityViewModels
import com.andrometa.pullout.databinding.SheetSettingsBinding
import com.andrometa.pullout.util.SettingsRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsSheet : BottomSheetDialogFragment() {
    private var _b: SheetSettingsBinding? = null
    private val b get() = _b!!
    private val vm: DownloadQueueViewModel by activityViewModels()
    private lateinit var settings: SettingsRepository
    var onCobaltUrlChanged: ((String) -> Unit)? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        SheetSettingsBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = SettingsRepository(requireContext())
        _b?.etCobaltUrl?.setText(settings.cobaltInstanceUrl)
        _b?.switchAudioOnly?.isChecked = settings.audioOnlyMode

        val qualities = listOf("max", "2160", "1440", "1080", "720", "480", "360")
        val qAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, qualities)
        qAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        _b?.spinnerQuality?.adapter = qAdapter
        _b?.spinnerQuality?.setSelection(qualities.indexOf(settings.defaultQuality).coerceAtLeast(0))

        _b?.switchAudioOnly?.setOnCheckedChangeListener { _, checked -> settings.audioOnlyMode = checked }
        _b?.spinnerQuality?.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                settings.defaultQuality = qualities[pos]
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }
        _b?.btnBattery?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            })
        }
        _b?.btnClearHistory?.setOnClickListener { vm.clearHistory(); dismiss() }
        _b?.btnDocs?.setOnClickListener {
            CustomTabsIntent.Builder()
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                .setToolbarColor(Color.parseColor("#0E0E16"))
                .build()
                .launchUrl(requireContext(), Uri.parse("https://github.com/imputnet/cobalt/blob/main/docs/api.md"))
        }
    }

    override fun onStop() {
        super.onStop()
        // Use _b? to avoid NPE if view destroyed before onStop
        val url = _b?.etCobaltUrl?.text?.toString()?.trim() ?: return
        if (url.isNotBlank() && url != settings.cobaltInstanceUrl) {
            settings.cobaltInstanceUrl = url
            onCobaltUrlChanged?.invoke(url)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
    companion object { const val TAG = "SettingsSheet"; fun newInstance() = SettingsSheet() }
}
