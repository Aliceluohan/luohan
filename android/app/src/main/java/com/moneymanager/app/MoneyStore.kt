package com.moneymanager.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Single, synchronized owner of the on-device ledger. Both the WebView and the
 * background capture services go through here so a late WebView save cannot
 * silently erase a transaction captured in the background.
 */
object MoneyStore {
    const val DB_PREFS = "moneymanager_db"
    const val DB_KEY = "db"
    private const val DIAG_KEY = "capture_diagnostics"
    private const val MAX_DIAGNOSTICS = 60

    @Synchronized
    fun load(context: Context): String = loadObject(context).toString()

    @Synchronized
    fun saveFromWeb(context: Context, json: String): String {
        val incoming = runCatching { JSONObject(json) }.getOrElse { blankDb() }
        val current = loadObject(context)
        val incomingRev = incoming.optLong("_rev", 0)
        val currentRev = current.optLong("_rev", 0)

        // A notification may have arrived after the page loaded. Preserve native
        // additions when the page attempts to save an older snapshot.
        if (incomingRev < currentRev) {
            mergeMissingById(incoming, current, "tx")
            mergeMissingById(incoming, current, "reports")
        }
        ensureShape(incoming)
        incoming.put("_rev", currentRev + 1)
        persist(context, incoming)
        return incoming.toString()
    }

    @Synchronized
    fun recordTransaction(
        context: Context,
        parsed: ParsedTx,
        source: String,
        packageName: String,
        externalId: String,
        eventTimeMs: Long = System.currentTimeMillis()
    ): RecordResult {
        val db = loadObject(context)
        val tx = db.getJSONArray("tx")

        for (i in 0 until tx.length()) {
            val old = tx.optJSONObject(i) ?: continue
            if (externalId.isNotBlank() && old.optString("externalId") == externalId) {
                addDiagnostic(context, packageName, source, "duplicate", parsed)
                return RecordResult.Duplicate
            }
        }

        // Fuse evidence from different capture channels: a bank notification may
        // know the amount while the payment-result screen knows the real merchant.
        val mergeCandidate = (0 until tx.length()).mapNotNull { tx.optJSONObject(it) }
            .filter { it.optString("type", "expense") == parsed.type }
            .filter { abs(it.optDouble("amount", -1.0) - parsed.amount) < 0.005 }
            .filter { abs(parseStoredTime(it.optString("time")) - eventTimeMs) <= 90_000L }
            .firstOrNull { old ->
                val oldSources = old.optJSONArray("sources") ?: JSONArray().put(old.optString("captureSource"))
                (0 until oldSources.length()).none { oldSources.optString(it) == source }
            }

        if (mergeCandidate != null) {
            val oldMerchant = mergeCandidate.optString("merchant")
            if (merchantScore(parsed.merchant) > merchantScore(oldMerchant)) {
                mergeCandidate.put("merchant", parsed.merchant)
                val (cat, confident) = classify(db, parsed.merchant, parsed.rawText, parsed.type)
                mergeCandidate.put("category", cat)
                mergeCandidate.put("needsReview", !confident)
            }
            val sources = mergeCandidate.optJSONArray("sources") ?: JSONArray().also {
                val oldSource = mergeCandidate.optString("captureSource")
                if (oldSource.isNotBlank()) it.put(oldSource)
                mergeCandidate.put("sources", it)
            }
            sources.put(source)
            mergeCandidate.put("confidence", maxOf(mergeCandidate.optInt("confidence", 0), parsed.confidence))
            bumpAndPersist(context, db)
            addDiagnostic(context, packageName, source, "merged", parsed)
            return RecordResult.Merged
        }

        val (category, confident) = classify(db, parsed.merchant, parsed.rawText, parsed.type)
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(Date(eventTimeMs))
        val fp = NotificationParser.fingerprint(parsed.type, time, parsed.amount, parsed.merchant)
        val rec = JSONObject().apply {
            put("id", UUID.randomUUID().toString().replace("-", "").take(16))
            put("amount", parsed.amount)
            put("merchant", parsed.merchant)
            put("type", parsed.type)
            put("category", category)
            put("needsReview", !confident || parsed.confidence < 70)
            put("time", time)
            put("source", "auto")
            put("captureSource", source)
            put("sources", JSONArray().put(source))
            put("sourcePackage", packageName)
            put("externalId", externalId)
            put("confidence", parsed.confidence)
            put("orderNo", "")
            put("note", "")
            put("fp", fp)
        }
        tx.put(rec)
        bumpAndPersist(context, db)
        addDiagnostic(context, packageName, source, "recorded", parsed)
        return RecordResult.Added
    }

    @Synchronized
    fun recordIgnored(context: Context, packageName: String, source: String, reason: String) {
        addDiagnostic(context, packageName, source, reason, null)
    }

    @Synchronized
    fun diagnostics(context: Context): String {
        val prefs = context.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(DIAG_KEY, "[]") ?: "[]"
    }

    @Synchronized
    fun clearDiagnostics(context: Context) {
        context.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE).edit().remove(DIAG_KEY).apply()
    }

    private fun classify(db: JSONObject, merchant: String, raw: String, type: String): Pair<String, Boolean> {
        val memory = db.optJSONObject("memory") ?: JSONObject()
        val norm = NotificationParser.normalize(merchant)
        val memoryKey = if (type == "income") "$norm::in" else norm
        val remembered = memory.optString(memoryKey)
        if (remembered.isNotBlank()) return remembered to true
        return Classifier.classify("$merchant $raw", type)
    }

    private fun merchantScore(value: String): Int {
        if (value.isBlank()) return 0
        val generic = listOf("自动记录", "未知商户", "微信支付", "支付宝", "财付通", "中信银行")
        if (generic.any { value == it || value.contains("${it}快捷支付") }) return 1
        return 3
    }

    private fun parseStoredTime(value: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(value)?.time ?: 0L
    }.getOrDefault(0L)

    private fun addDiagnostic(
        context: Context,
        packageName: String,
        source: String,
        result: String,
        parsed: ParsedTx?
    ) {
        val prefs = context.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(DIAG_KEY, "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray()
        next.put(JSONObject().apply {
            put("at", SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("package", packageName)
            put("source", source)
            put("result", result)
            if (parsed != null) {
                put("amount", parsed.amount)
                put("merchant", parsed.merchant)
                put("type", parsed.type)
                put("confidence", parsed.confidence)
            }
        })
        for (i in 0 until minOf(old.length(), MAX_DIAGNOSTICS - 1)) next.put(old.opt(i))
        prefs.edit().putString(DIAG_KEY, next.toString()).apply()
    }

    private fun loadObject(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(DB_KEY, null)
        val db = if (raw.isNullOrBlank()) blankDb() else runCatching { JSONObject(raw) }.getOrElse { blankDb() }
        ensureShape(db)
        return db
    }

    private fun blankDb() = JSONObject().apply {
        put("tx", JSONArray())
        put("memory", JSONObject())
        put("pref", JSONObject())
        put("reports", JSONArray())
        put("v", 1)
        put("_rev", 0)
    }

    private fun ensureShape(db: JSONObject) {
        if (db.optJSONArray("tx") == null) db.put("tx", JSONArray())
        if (db.optJSONObject("memory") == null) db.put("memory", JSONObject())
        if (db.optJSONObject("pref") == null) db.put("pref", JSONObject())
        if (db.optJSONArray("reports") == null) db.put("reports", JSONArray())
        db.put("v", 1)
    }

    private fun mergeMissingById(target: JSONObject, source: JSONObject, field: String) {
        val targetArray = target.optJSONArray(field) ?: JSONArray().also { target.put(field, it) }
        val ids = (0 until targetArray.length()).mapNotNull { targetArray.optJSONObject(it)?.optString("id") }.toSet()
        val sourceArray = source.optJSONArray(field) ?: return
        for (i in 0 until sourceArray.length()) {
            val item = sourceArray.optJSONObject(i) ?: continue
            if (item.optString("id") !in ids) targetArray.put(item)
        }
    }

    private fun bumpAndPersist(context: Context, db: JSONObject) {
        db.put("_rev", db.optLong("_rev", 0) + 1)
        persist(context, db)
    }

    private fun persist(context: Context, db: JSONObject) {
        context.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)
            .edit().putString(DB_KEY, db.toString()).apply()
    }

    enum class RecordResult { Added, Merged, Duplicate }
}
