package com.moneymanager.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object ReportScheduler {
    private const val CHANNEL_ID = "money_reports"

    fun scheduleAll(context: Context) {
        val pref = runCatching { JSONObject(MoneyStore.load(context)).optJSONObject("pref") }
            .getOrNull() ?: JSONObject()
        schedule(context, "week", pref.optString("reportTimeWeek", "09:00"), false)
        schedule(context, "month", pref.optString("reportTimeMonth", "09:00"), false)
    }

    fun scheduleAfterRun(context: Context, type: String) {
        val pref = runCatching { JSONObject(MoneyStore.load(context)).optJSONObject("pref") }
            .getOrNull() ?: JSONObject()
        val value = if (type == "week") pref.optString("reportTimeWeek", "09:00")
            else pref.optString("reportTimeMonth", "09:00")
        schedule(context, type, value, true)
    }

    private fun schedule(context: Context, type: String, hhmm: String, append: Boolean) {
        val parts = hhmm.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 9
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val now = LocalDateTime.now()
        var target = if (type == "week") {
            now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atTime(hour, minute)
        } else {
            now.toLocalDate().withDayOfMonth(1).atTime(hour, minute)
        }
        if (!target.isAfter(now)) {
            target = if (type == "week") target.plusWeeks(1) else target.plusMonths(1)
        }
        val delay = Duration.between(now, target).toMillis().coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<ReportWorker>()
            .setInputData(Data.Builder().putString("type", type).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "money_report_$type",
            if (append) androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE else androidx.work.ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "周报与月报", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "在周一和每月月初发送上一期消费小结"
            }
        )
    }

    fun notifyReport(context: Context, title: String, body: String, id: Int) {
        ensureNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_money)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

class ReportWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val type = inputData.getString("type") ?: return Result.failure()
        return try {
            generate(type)
            ReportScheduler.scheduleAfterRun(applicationContext, type)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun generate(type: String) {
        val db = JSONObject(MoneyStore.load(applicationContext))
        val pref = db.optJSONObject("pref") ?: JSONObject().also { db.put("pref", it) }
        val reports = db.optJSONArray("reports") ?: org.json.JSONArray().also { db.put("reports", it) }
        val today = LocalDate.now()
        val from: LocalDate
        val to: LocalDate
        val prevFrom: LocalDate
        val key: String
        val label: String

        if (type == "week") {
            to = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            from = to.minusWeeks(1)
            prevFrom = from.minusWeeks(1)
            key = from.toString()
            if (pref.optString("reportedWeekKey") == key) return
            label = if (pref.optString("lang", "zh") == "en") {
                "${from.month.name.lowercase().replaceFirstChar { it.titlecase() }} ${from.dayOfMonth} – ${to.minusDays(1).month.name.lowercase().replaceFirstChar { it.titlecase() }} ${to.minusDays(1).dayOfMonth}"
            } else "${from.monthValue}月${from.dayOfMonth}日 – ${to.minusDays(1).monthValue}月${to.minusDays(1).dayOfMonth}日"
            pref.put("reportedWeekKey", key)
        } else {
            to = today.withDayOfMonth(1)
            from = to.minusMonths(1)
            prevFrom = from.minusMonths(1)
            key = "%04d-%02d".format(Locale.US, from.year, from.monthValue)
            if (pref.optString("reportedMonthKey") == key) return
            label = if (pref.optString("lang", "zh") == "en") {
                "${from.month.name.lowercase().replaceFirstChar { it.titlecase() }} ${from.year}"
            } else "${from.year}年${from.monthValue}月"
            pref.put("reportedMonthKey", key)
        }

        val current = aggregate(db, from, to)
        val previous = aggregate(db, prevFrom, from)
        val english = pref.optString("lang", "zh") == "en"
        val text = compose(current, previous.total, english)
        if (text.isNotBlank()) {
            reports.put(JSONObject().apply {
                put("id", UUID.randomUUID().toString().replace("-", "").take(16))
                put("type", type)
                put("periodLabel", label)
                put("text", text)
                put("read", false)
                put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
            })
        }
        MoneyStore.saveFromWeb(applicationContext, db.toString())
        if (text.isNotBlank()) {
            val title = if (english) {
                if (type == "week") "Your weekly summary is ready" else "Your monthly summary is ready"
            } else {
                if (type == "week") "你的周报已生成" else "你的月报已生成"
            }
            ReportScheduler.notifyReport(applicationContext, title, text, if (type == "week") 4101 else 4102)
        }
    }

    private data class Agg(val total: Double, val income: Double, val rows: List<Pair<String, Double>>)

    private fun aggregate(db: JSONObject, from: LocalDate, to: LocalDate): Agg {
        var total = 0.0
        var income = 0.0
        val categories = linkedMapOf<String, Double>()
        val tx = db.optJSONArray("tx") ?: return Agg(0.0, 0.0, emptyList())
        for (i in 0 until tx.length()) {
            val item = tx.optJSONObject(i) ?: continue
            val date = runCatching { LocalDateTime.parse(item.optString("time")).toLocalDate() }.getOrNull() ?: continue
            if (date.isBefore(from) || !date.isBefore(to)) continue
            val amount = item.optDouble("amount", 0.0)
            if (item.optString("type", "expense") == "income") income += amount else {
                total += amount
                val cat = item.optString("category", "其他")
                categories[cat] = (categories[cat] ?: 0.0) + amount
            }
        }
        return Agg(total, income, categories.entries.sortedByDescending { it.value }.map { it.key to it.value })
    }

    private fun compose(a: Agg, previous: Double, english: Boolean): String {
        if (a.total <= 0 && a.income <= 0) return ""
        val money: (Double) -> String = { String.format(Locale.US, "%.2f", it) }
        if (english) {
            val parts = mutableListOf(if (a.total > 0) "You spent ¥${money(a.total)}" else "No spending")
            if (a.income > 0) parts += "income ¥${money(a.income)}"
            a.rows.firstOrNull()?.let { parts += "largest: ${it.first} ¥${money(it.second)}" }
            if (previous > 0 && a.total > 0) parts += if (a.total >= previous) {
                "¥${money(a.total - previous)} more than last period"
            } else "¥${money(previous - a.total)} less than last period"
            return parts.joinToString(", ") + "."
        }
        val parts = mutableListOf(if (a.total > 0) "这段时间一共花了 ¥${money(a.total)}" else "这段时间没有支出")
        if (a.income > 0) parts += "收入 ¥${money(a.income)}"
        a.rows.firstOrNull()?.let { parts += "大头是「${it.first}」¥${money(it.second)}"
        }
        if (previous > 0 && a.total > 0) parts += if (a.total >= previous) {
            "比上一期多花 ¥${money(a.total - previous)}"
        } else "比上一期少花 ¥${money(previous - a.total)}"
        return parts.joinToString("，") + "。"
    }
}
