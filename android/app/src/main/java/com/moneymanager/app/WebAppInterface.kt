package com.moneymanager.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.webkit.JavascriptInterface

/**
 * 网页(assets/www/index.html)通过 window.AndroidBridge 调用这里。
 * load()/save() 读写的这份 SharedPreferences，NotificationCaptureService
 * 也在直接读写同一份，两边永远是同一份数据。
 */
class WebAppInterface(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)

    @JavascriptInterface
    fun load(): String = prefs.getString(DB_KEY, "") ?: ""

    @JavascriptInterface
    fun save(json: String) {
        prefs.edit().putString(DB_KEY, json).apply()
    }

    @JavascriptInterface
    fun isNotificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        return enabled != null && enabled.contains(ctx.packageName)
    }

    @JavascriptInterface
    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    companion object {
        const val DB_PREFS = "moneymanager_db"
        const val DB_KEY = "db"
    }
}
