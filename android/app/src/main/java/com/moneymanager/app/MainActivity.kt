package com.moneymanager.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")
        webView.loadUrl("file:///android_asset/www/index.html")

        maybeRequestNotificationAccessOnce()
    }

    override fun onResume() {
        super.onResume()
        // 从后台切回来时，把通知监听服务这段时间写入的新记录拉进来
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "window.reloadFromBridge && window.reloadFromBridge();",
                null
            )
        }
    }

    private fun maybeRequestNotificationAccessOnce() {
        val prefs = getSharedPreferences("moneymanager_app", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notif_once", false)) return
        prefs.edit().putBoolean("asked_notif_once", true).apply()

        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (enabled == null || !enabled.contains(packageName)) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }
}
