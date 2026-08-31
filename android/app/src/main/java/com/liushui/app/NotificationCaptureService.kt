package com.liushui.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NotificationCaptureService : NotificationListenerService() {

    private val watchedPackages = setOf(
        "com.tencent.mm",               // 微信
        "com.eg.android.AlipayGphone"   // 支付宝
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName !in watchedPackages) return
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val full = listOf(title, text, bigText).filter { it.isNotBlank() }.joinToString(" ")
            if (full.isBlank()) return

            val parsed = NotificationParser.parse(full) ?: return
            recordTransaction(parsed)
        } catch (e: Exception) {
            Log.e("liushui", "记账通知解析失败", e)
        }
    }

    private fun recordTransaction(p: ParsedTx) {
        val prefs = getSharedPreferences(WebAppInterface.DB_PREFS, MODE_PRIVATE)
        val raw = prefs.getString(WebAppInterface.DB_KEY, null)
        val db = if (raw != null) JSONObject(raw) else JSONObject().apply {
            put("tx", JSONArray())
            put("memory", JSONObject())
            put("v", 1)
        }
        val txArray = db.optJSONArray("tx") ?: JSONArray().also { db.put("tx", it) }
        val memory = db.optJSONObject("memory") ?: JSONObject().also { db.put("memory", it) }

        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(Date())
        val normMerchant = NotificationParser.normalize(p.merchant)
        val memKey = if (p.type == "income") "$normMerchant::in" else normMerchant
        val remembered = memory.optString(memKey, "")

        // 分类优先级：记住的商户 > 关键词规则 > 归到"其他"待确认
        val category: String
        val needsReview: Boolean
        if (remembered.isNotBlank()) {
            category = remembered
            needsReview = false
        } else {
            val (guessedCat, confident) = Classifier.classify("${p.merchant} ${p.rawText}", p.type)
            category = guessedCat
            needsReview = !confident
        }

        val fp = NotificationParser.fingerprint(p.type, time, p.amount, p.merchant)
        for (i in 0 until txArray.length()) {
            if (txArray.optJSONObject(i)?.optString("fp") == fp) return // 去重
        }

        val rec = JSONObject().apply {
            put("id", UUID.randomUUID().toString().replace("-", "").take(16))
            put("amount", p.amount)
            put("merchant", p.merchant)
            put("type", p.type)
            put("category", category)
            put("needsReview", needsReview)
            put("time", time)
            put("source", "auto")
            put("orderNo", "")
            put("note", "")
            put("fp", fp)
        }
        txArray.put(rec)
        prefs.edit().putString(WebAppInterface.DB_KEY, db.toString()).apply()
    }
}
