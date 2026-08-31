package com.liushui.app

data class ParsedTx(val amount: Double, val merchant: String, val type: String, val rawText: String)

/**
 * 和网页里 parseFromText() / normalize() / fingerprint() 保持同样的逻辑，
 * 只是这份跑在通知监听服务里（app 没打开也能执行），不依赖 WebView。
 */
object NotificationParser {

    private val amountPatterns = listOf(
        Regex("[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)"),
        Regex("(?:金额|付款|支付|消费|扣款|支出)[^\\d]{0,6}(\\d+(?:\\.\\d{1,2})?)"),
        Regex("(\\d+\\.\\d{2})\\s*元?"),
        Regex("(\\d+)\\s*元")
    )

    private val merchantPatterns = listOf(
        Regex("(?:向|在|于)\\s*([^\\s,，。；;]{2,24}?)\\s*(?:付款|消费|支付|转账)"),
        Regex("(?:商户|收款方|对方|付款给)[：:]\\s*([^\\s,，。；;]{2,24})"),
        Regex("([^\\s,，。；;]{2,24}?)(?:交易成功|付款成功|支付成功)"),
        Regex("(?:来自|收到)\\s*([^\\s,，。；;]{2,24}?)\\s*(?:的转账|的红包|付款)")
    )

    private val incomeHint = Regex("收款|到账|入账|已存入|工资入账|收到.{0,10}(转账|红包)")
    private val expenseHint = Regex("付款|消费|支付成功|扣款|支出")

    fun parse(text: String): ParsedTx? {
        var amount: Double? = null
        for (p in amountPatterns) {
            val m = p.find(text) ?: continue
            amount = m.groupValues[1].toDoubleOrNull()
            if (amount != null) break
        }
        if (amount == null || amount <= 0.0) return null

        var merchant = ""
        for (p in merchantPatterns) {
            val m = p.find(text) ?: continue
            val candidate = m.groupValues[1].trim()
            if (candidate.isNotEmpty()) { merchant = candidate; break }
        }

        val type = if (incomeHint.containsMatchIn(text) && !expenseHint.containsMatchIn(text)) {
            "income"
        } else {
            "expense"
        }
        return ParsedTx(amount, merchant.ifBlank { "自动记录" }, type, text)
    }

    fun normalize(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return s.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[（）()【】\\[\\]·、,，.。\"'‘’“”-]"), "")
    }

    fun fingerprint(type: String, time: String, amount: Double, merchant: String): String {
        val timePart = if (time.length >= 16) time.substring(0, 16) else time
        val normed = normalize(merchant)
        val merchPart = if (normed.length > 14) normed.substring(0, 14) else normed
        val typePart = if (type == "income") "in" else "ex"
        val amountPart = String.format(java.util.Locale.US, "%.2f", amount)
        return "f:$typePart|$timePart|$amountPart|$merchPart"
    }
}
