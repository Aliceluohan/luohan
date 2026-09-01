package com.moneymanager.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 页面加载完、拿到真正主题色之前，先用默认"存折"配色顶一下，
        // 不然系统状态栏/导航栏会先黑一下再变色
        window.statusBarColor = Color.parseColor("#F1EAD9")
        window.navigationBarColor = Color.parseColor("#F1EAD9")
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

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
