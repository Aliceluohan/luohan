package com.moneymanager.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface

/**
 * 网页(assets/www/index.html)通过 window.AndroidBridge 调用这里。
 * load()/save() 读写的这份 SharedPreferences，NotificationCaptureService
 * 也在直接读写同一份，两边永远是同一份数据。
 */
class WebAppInterface(private val activity: Activity) {

    private val prefs = activity.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)

    @JavascriptInterface
    fun load(): String = prefs.getString(DB_KEY, "") ?: ""

    @JavascriptInterface
    fun save(json: String) {
        prefs.edit().putString(DB_KEY, json).apply()
    }

    @JavascriptInterface
    fun isNotificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners")
        return enabled != null && enabled.contains(activity.packageName)
    }

    @JavascriptInterface
    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    // 网页切换主题时调用，让系统状态栏/导航栏跟着换成同一个底色，不然会露出系统默认的黑色
    @JavascriptInterface
    fun setSystemBarColor(hex: String) {
        activity.runOnUiThread {
            try {
                val color = Color.parseColor(hex)
                val window = activity.window
                window.statusBarColor = color
                window.navigationBarColor = color
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } catch (e: Exception) {
                // 颜色字符串解析失败就不管它，保留上一次的颜色
            }
        }
    }

    companion object {
        const val DB_PREFS = "moneymanager_db"
        const val DB_KEY = "db"
    }
}
