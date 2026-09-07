package com.moneymanager.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional, user-enabled capture for payment-result screens. It is deliberately
 * read-only, scoped to WeChat/Alipay by XML, and never stores the full UI text.
 */
class PaymentAccessibilityService : AccessibilityService() {
    private var lastDigest = ""
    private var lastHandledAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "com.tencent.mm" && pkg != "com.eg.android.AlipayGphone") return
        val now = System.currentTimeMillis()
        if (now - lastHandledAt < 500L) return

        // Do not walk an ordinary chat or home screen. Only an accessibility
        // event that itself announces a payment result unlocks the narrow,
        // one-shot scan needed to find the amount and payee around it.
        val eventSignal = buildString {
            event.text.forEach { append(it).append(' ') }
            append(event.contentDescription ?: "")
        }
        if (!Regex("支付成功|付款成功|交易成功|转账成功|红包已发送|已发送红包|收款成功")
                .containsMatchIn(eventSignal)) return

        try {
            val root = rootInActiveWindow ?: event.source ?: return
            val text = collectText(root)
            if (text.length < 4) return
            val digest = "$pkg:${text.hashCode()}"
            if (digest == lastDigest && now - lastHandledAt < 8_000L) return
            lastDigest = digest
            lastHandledAt = now

            val parsed = NotificationParser.parse(text, requireSuccess = true) ?: return
            val source = if (pkg == "com.tencent.mm") "wechat_screen" else "alipay_screen"
            MoneyStore.recordTransaction(
                this,
                parsed,
                source,
                pkg,
                "$source:${text.hashCode()}",
                now
            )
        } catch (e: Exception) {
            Log.e("moneymanager", "支付结果页面识别失败", e)
        }
    }

    override fun onInterrupt() = Unit

    private fun collectText(root: AccessibilityNodeInfo): String {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 260) {
            val node = queue.removeFirst()
            visited++
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return out.joinToString(" ").take(2_000)
    }
}
