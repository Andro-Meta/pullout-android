package com.andrometa.pullout.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.andrometa.pullout.databinding.ActivityLoginBinding

/**
 * Full-screen WebView login. Loads a service's login URL; after each page load
 * it checks CookieManager for the service's required cookies and, when present,
 * persists them into cobalt's cookies.json and returns RESULT_OK.
 */
@SuppressLint("SetJavaScriptEnabled")
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var serviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serviceId = intent.getStringExtra(EXTRA_SERVICE).orEmpty()
        val cfg = AuthServices.byId(serviceId)
        if (cfg == null) {
            Log.e(TAG, "unknown service '$serviceId'")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        binding.tvLoginService.text = cfg.displayName
        binding.btnLoginClose.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tryCapture()
            }
        }
        binding.webView.loadUrl(cfg.loginUrl)
    }

    private fun tryCapture() {
        CookieManager.getInstance().flush()
        val cookieString = CookieStore.buildCookieStringFromManager(serviceId) ?: return
        CookieStore.saveService(applicationContext, serviceId, cookieString)
        Log.i(TAG, "captured login for $serviceId")
        val data = Intent().putExtra(EXTRA_SERVICE, serviceId)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack()
        else {
            setResult(Activity.RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    companion object {
        private const val TAG = "LoginActivity"
        const val EXTRA_SERVICE = "extra_service"
    }
}
