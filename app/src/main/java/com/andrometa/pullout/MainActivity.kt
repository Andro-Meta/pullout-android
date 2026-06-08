package com.andrometa.pullout

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.andrometa.pullout.api.CobaltApiClient
import com.andrometa.pullout.api.CobaltResponse
import com.andrometa.pullout.api.PickerItem
import com.andrometa.pullout.databinding.ActivityMainBinding
import com.andrometa.pullout.download.DownloadService
import com.andrometa.pullout.server.NodeServerManager
import com.andrometa.pullout.server.ServerState
import com.andrometa.pullout.ui.DownloadQueueSheet
import com.andrometa.pullout.ui.DownloadQueueViewModel
import com.andrometa.pullout.ui.PickerSheet
import com.andrometa.pullout.ui.SettingsSheet
import com.andrometa.pullout.util.ClipboardHelper
import com.andrometa.pullout.util.SettingsRepository
import com.andrometa.pullout.util.UrlMatcher
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private lateinit var apiClient: CobaltApiClient
    private val queueViewModel: DownloadQueueViewModel by viewModels()
    private var currentOriginalUrl = ""
    private var pulseAnim: Animation? = null

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* silent degradation if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository(this)
        apiClient = CobaltApiClient(settings)

        setupServerObserver()
        setupInputUi()
        setupFab()
        setupSettings()
        handleFirstLaunch()
        handleIntent(intent)

        // Attach the po_token capture WebView to this Activity's surface. YouTube's
        // player only initializes (and emits the /player request we harvest) when the
        // WebView has a real rendering surface — a detached/service WebView never lays
        // out the player. Attached at 1x1 so it's imperceptible.
        com.andrometa.pullout.auth.PoTokenManager.attachWebHost(this)
    }

    override fun onDestroy() {
        com.andrometa.pullout.auth.PoTokenManager.detachWebHost()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (settings.clipboardTriggerEnabled) checkClipboard()
    }

    // ── Server state ──────────────────────────────────────────────────────────

    private fun setupServerObserver() {
        NodeServerManager.serverState.observe(this) { state ->
            when (state) {
                is ServerState.Cold, is ServerState.Warming -> {
                    binding.tvStatus.text = getString(R.string.status_init)
                    binding.tvStatus.setTextColor(getColor(R.color.neon_amber))
                    binding.btnPull.isEnabled = false
                    startPulse()
                }
                is ServerState.Ready -> {
                    binding.tvStatus.text = getString(R.string.status_ready)
                    binding.tvStatus.setTextColor(getColor(R.color.neon_green))
                    binding.btnPull.isEnabled = true
                    stopPulse()
                }
                is ServerState.Error -> {
                    binding.tvStatus.text = getString(R.string.status_offline)
                    binding.tvStatus.setTextColor(getColor(R.color.neon_red))
                    binding.btnPull.isEnabled = false
                    stopPulse()
                    Snackbar.make(binding.root, getString(R.string.err_offline), Snackbar.LENGTH_LONG)
                        .setAction("RETRY") { com.andrometa.pullout.server.CobaltServerService.start(this) }
                        .setBackgroundTint(getColor(R.color.surface))
                        .setTextColor(getColor(R.color.text_primary))
                        .setActionTextColor(getColor(R.color.neon_cyan))
                        .show()
                }
            }
        }

        queueViewModel.activeDownloads.observe(this) { list ->
            val count = list.size
            if (count > 0) {
                binding.tvBadge.visibility = View.VISIBLE
                binding.tvBadge.text = count.toString()
            } else {
                binding.tvBadge.visibility = View.GONE
            }
        }
    }

    // ── Input UI ──────────────────────────────────────────────────────────────

    private fun setupInputUi() {
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            binding.etUrl.background = if (hasFocus)
                getDrawable(R.drawable.bg_input_focused)
            else
                getDrawable(R.drawable.bg_input_normal)
        }

        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitFromInput(); true
            } else false
        }

        binding.btnPull.setOnClickListener { submitFromInput() }
    }

    private fun submitFromInput() {
        val text = binding.etUrl.text?.toString() ?: return
        val url = UrlMatcher.extractUrl(text)
        if (url == null) {
            showError(getString(R.string.err_invalid_url))
            return
        }
        submitUrl(url)
    }

    private fun submitUrl(url: String) {
        if (!NodeServerManager.isReady()) {
            showError(getString(R.string.err_offline))
            return
        }
        currentOriginalUrl = url
        binding.tvStatus.text = getString(R.string.status_working)
        binding.tvStatus.setTextColor(getColor(R.color.neon_cyan))
        binding.btnPull.isEnabled = false
        hideError()

        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                try { apiClient.submit(url) }
                catch (e: Exception) { CobaltResponse.CobaltError("api.unreachable", null) }
            }

            // Restore button state
            binding.btnPull.isEnabled = NodeServerManager.isReady()
            binding.tvStatus.text = if (NodeServerManager.isReady()) getString(R.string.status_ready) else getString(R.string.status_offline)
            binding.tvStatus.setTextColor(getColor(if (NodeServerManager.isReady()) R.color.neon_green else R.color.neon_red))

            when (response) {
                is CobaltResponse.Tunnel -> {
                    DownloadService.startDirect(this@MainActivity, response.url, response.filename, response.mimeType, url)
                    binding.etUrl.text?.clear()
                }
                is CobaltResponse.Redirect -> {
                    DownloadService.startDirect(this@MainActivity, response.url, response.filename, response.mimeType, url)
                    binding.etUrl.text?.clear()
                }
                is CobaltResponse.LocalProcessing -> {
                    DownloadService.startMux(this@MainActivity, response, url)
                    binding.etUrl.text?.clear()
                }
                is CobaltResponse.Picker -> {
                    showPicker(response.items, url)
                }
                is CobaltResponse.CobaltError -> {
                    showError(translateCobaltError(response.code))
                }
            }
        }
    }

    // ── FAB + Sheets ──────────────────────────────────────────────────────────

    private fun setupFab() {
        binding.fabQueue.setOnClickListener {
            DownloadQueueSheet.newInstance().also { sheet ->
                sheet.onRetry = { record -> submitUrl(record.originalUrl) }
                sheet.show(supportFragmentManager, DownloadQueueSheet.TAG)
            }
        }
    }

    private fun setupSettings() {
        binding.tvSettings.setOnClickListener {
            SettingsSheet.newInstance().also { sheet ->
                sheet.onCobaltUrlChanged = { /* URL changed; CobaltApiClient reads from SettingsRepository live */ }
                sheet.onCookiesChanged = { NodeServerManager.restartServer(this) }
                sheet.show(supportFragmentManager, SettingsSheet.TAG)
            }
        }
    }

    private fun showPicker(items: List<PickerItem>, originalUrl: String) {
        PickerSheet.newInstance(items).also { sheet ->
            sheet.onItemsSelected = { selected ->
                selected.forEach { item ->
                    DownloadService.startDirect(this, item.url,
                        "pullout_${System.currentTimeMillis()}.${if (item.type == "video") "mp4" else "jpg"}",
                        if (item.type == "video") "video/mp4" else "image/jpeg",
                        originalUrl)
                }
            }
            sheet.show(supportFragmentManager, PickerSheet.TAG)
        }
    }

    // ── Clipboard ─────────────────────────────────────────────────────────────

    private fun checkClipboard() {
        val url = ClipboardHelper.getSupportedUrl(this) ?: return
        Snackbar.make(binding.root, getString(R.string.clip_prompt), Snackbar.LENGTH_LONG)
            .setAction(getString(R.string.clip_action)) { submitUrl(url) }
            .setBackgroundTint(getColor(R.color.surface))
            .setTextColor(getColor(R.color.text_primary))
            .setActionTextColor(getColor(R.color.neon_cyan))
            .show()
    }

    // ── Intent handling ───────────────────────────────────────────────────────

    private fun handleIntent(intent: Intent?) {
        when {
            intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val url = UrlMatcher.extractUrl(text)
                if (url != null) {
                    binding.etUrl.setText(url)
                    if (NodeServerManager.isReady()) submitUrl(url)
                    else {
                        // Server not ready; URL is in the field, user taps PULL when ready
                        showError(getString(R.string.err_offline))
                    }
                } else {
                    showError(getString(R.string.err_invalid_url))
                }
            }
            intent?.getStringExtra("shortcut_paste") == "true" ||
            intent?.getBooleanExtra("shortcut_paste", false) == true -> {
                val url = ClipboardHelper.getSupportedUrl(this)
                if (url != null && NodeServerManager.isReady()) submitUrl(url)
                else if (url != null) binding.etUrl.setText(url)
            }
            intent?.getStringExtra("shortcut_queue") == "true" ||
            intent?.getBooleanExtra("shortcut_queue", false) == true -> {
                binding.fabQueue.performClick()
            }
        }
    }

    // ── Error display ─────────────────────────────────────────────────────────

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
        binding.tvError.postDelayed({ hideError() }, 5000)
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }

    private fun translateCobaltError(code: String): String = when {
        code.contains("link.invalid") || code.contains("link.unsupported") -> getString(R.string.err_link_unsupported)
        code.contains("noFiles") || code.contains("content.unavailable") -> getString(R.string.err_content_unavailable)
        code.contains("content.too_long") -> getString(R.string.err_content_too_long)
        code.contains("rate_limit") || code.contains("rateLimit") -> getString(R.string.err_rate_limit)
        code.contains("unreachable") -> getString(R.string.err_api_unreachable)
        else -> "${getString(R.string.err_generic)}: $code"
    }

    // ── Animations ────────────────────────────────────────────────────────────

    private fun startPulse() {
        if (pulseAnim != null) return
        pulseAnim = AlphaAnimation(1f, 0.3f).apply {
            duration = 800; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE
        }
        binding.tvStatus.startAnimation(pulseAnim)
    }

    private fun stopPulse() {
        binding.tvStatus.clearAnimation()
        pulseAnim = null
    }

    // ── First launch ──────────────────────────────────────────────────────────

    private fun handleFirstLaunch() {
        if (settings.firstLaunchDone) return
        settings.firstLaunchDone = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.battery_title))
                .setMessage(getString(R.string.battery_msg))
                .setPositiveButton(getString(R.string.allow)) { _, _ ->
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
                .setNegativeButton(getString(R.string.not_now), null)
                .show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0)
            supportFragmentManager.popBackStack()
        else super.onBackPressed()
    }
}
