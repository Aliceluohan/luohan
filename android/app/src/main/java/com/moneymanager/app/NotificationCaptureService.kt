package com.moneymanager.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
class NotificationCaptureService : NotificationListenerService() {

    private val watchedPackages = setOf(
        "com.tencent.mm",               // 微信
        "com.eg.android.AlipayGphone",  // 支付宝
        "com.ecitic.bank.mobile"        // 中信银行
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName !in watchedPackages) return
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString(" ") { it.toString() } ?: ""
            val full = listOf(title, text, bigText, subText, lines)
                .filter { it.isNotBlank() }.distinct().joinToString(" ")
            val source = when (sbn.packageName) {
                "com.ecitic.bank.mobile" -> "citic_notification"
                "com.tencent.mm" -> "wechat_notification"
                else -> "alipay_notification"
            }
            if (full.isBlank()) {
                MoneyStore.recordIgnored(this, sbn.packageName, source, "empty")
                return
            }

            val parsed = NotificationParser.parse(full, sourcePackage = sbn.packageName)
            if (parsed == null) {
                // Ordinary WeChat messages are deliberately ignored without even
                // creating a diagnostic entry. A bank notification is useful to
                // diagnose even when its wording is not recognized yet.
                if (sbn.packageName == "com.ecitic.bank.mobile" || NotificationParser.looksFinancial(full)) {
                    MoneyStore.recordIgnored(this, sbn.packageName, source, "not_a_transaction")
                }
                return
            }
            MoneyStore.recordTransaction(
                this,
                parsed,
                source,
                sbn.packageName,
                "${sbn.packageName}:${sbn.key}",
                sbn.postTime
            )
        } catch (e: Exception) {
            Log.e("moneymanager", "记账通知解析失败", e)
        }
    }

}
