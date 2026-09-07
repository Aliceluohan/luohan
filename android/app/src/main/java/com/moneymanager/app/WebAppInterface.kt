package com.moneymanager.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 网页(assets/www/index.html)通过 window.AndroidBridge 调用这里。
 * load()/save() 读写的这份 SharedPreferences，NotificationCaptureService
 * 也在直接读写同一份，两边永远是同一份数据。
 */
class WebAppInterface(private val activity: Activity) {

    @JavascriptInterface
    fun load(): String = MoneyStore.load(activity)

    @JavascriptInterface
    fun save(json: String): String {
        return MoneyStore.saveFromWeb(activity, json)
    }

    @JavascriptInterface
    fun scheduleReports() = ReportScheduler.scheduleAll(activity)

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

    @JavascriptInterface
    fun isAccessibilityAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = "${activity.packageName}/${PaymentAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    @JavascriptInterface
    fun openAccessibilitySettings() {
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    @JavascriptInterface
    fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            activity,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    @JavascriptInterface
    fun requestPostNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.runOnUiThread {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1042
                )
            }
        }
    }

    @JavascriptInterface
    fun getCaptureDiagnostics(): String = MoneyStore.diagnostics(activity)

    @JavascriptInterface
    fun clearCaptureDiagnostics() = MoneyStore.clearDiagnostics(activity)

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

}
