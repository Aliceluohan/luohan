package com.moneymanager.app

data class ParsedTx(
    val amount: Double,
    val merchant: String,
    val type: String,
    val rawText: String,
    val confidence: Int = 70
)

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
        Regex("(?:交易商户|商户名称|收款商户|商户)[：:]\\s*([^,，。；;\\n]{2,30})"),
        Regex("(?:收款方|付款给|转给|给)[：:]?\\s*([^\\s,，。；;]{1,24})"),
        Regex("(?:向|在|于)\\s*([^\\s,，。；;]{2,24}?)\\s*(?:付款|消费|支付|转账)"),
        Regex("(?:商户|收款方|对方|付款给)[：:]\\s*([^\\s,，。；;]{2,24})"),
        Regex("([^\\s,，。；;]{2,24}?)(?:交易成功|付款成功|支付成功)"),
        Regex("(?:来自|收到)\\s*([^\\s,，。；;]{2,24}?)\\s*(?:的转账|的红包|付款)")
    )

    private val incomeHint = Regex("收款|到账|入账|已存入|工资入账|收到.{0,10}(转账|红包)")
    private val expenseHint = Regex("付款|消费|支付成功|扣款|支出")
    private val transactionHint = Regex("付款|消费|支付|扣款|支出|交易|红包|转账|收款|到账|入账|工资")
    private val successHint = Regex("支付成功|付款成功|交易成功|转账成功|红包已发送|已发送红包|收款成功")

    fun looksFinancial(text: String): Boolean = transactionHint.containsMatchIn(text)

    fun parse(text: String, requireSuccess: Boolean = false, sourcePackage: String = ""): ParsedTx? {
        if (!transactionHint.containsMatchIn(text)) return null
        if (requireSuccess && !successHint.containsMatchIn(text)) return null
        if (sourcePackage == "com.ecitic.bank.mobile") {
            val accountContext = Regex("账户|银行卡|借记卡|信用卡|尾号|卡号")
            val movement = Regex("支出|消费|扣款|支付|入账|收入|转入|到账|交易")
            if (!accountContext.containsMatchIn(text) || !movement.containsMatchIn(text)) return null
        }
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
        val finalMerchant = merchant.ifBlank {
            when {
                text.contains("微信") || text.contains("财付通") -> "微信支付"
                text.contains("支付宝") -> "支付宝"
                text.contains("中信") -> "中信银行"
                else -> "自动记录"
            }
        }
        var confidence = 55
        if (merchant.isNotBlank()) confidence += 20
        if (successHint.containsMatchIn(text)) confidence += 15
        return ParsedTx(amount, finalMerchant, type, text, confidence.coerceAtMost(95))
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
