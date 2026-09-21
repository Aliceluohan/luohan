package com.moneymanager.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Switches the launcher entry without changing the app's data or restarting its activity. */
object LauncherIconManager {
    private const val LEDGER = "ledger"
    private const val RECEIPT = "receipt"

    private fun alias(context: Context, icon: String): ComponentName = ComponentName(
        context.packageName,
        "${context.packageName}." + if (icon == RECEIPT) "LauncherReceipt" else "LauncherLedger"
    )

    fun current(context: Context): String {
        val pm = context.packageManager
        return if (pm.getComponentEnabledSetting(alias(context, RECEIPT)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED) RECEIPT else LEDGER
    }

    fun switchTo(context: Context, icon: String): Boolean {
        if (icon != LEDGER && icon != RECEIPT) return false
        if (current(context) == icon) return true

        val pm = context.packageManager
        val chosen = alias(context, icon)
        val previous = alias(context, if (icon == LEDGER) RECEIPT else LEDGER)
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.setComponentEnabledSettings(listOf(
                    PackageManager.ComponentEnabledSetting(
                        chosen,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    ),
                    PackageManager.ComponentEnabledSetting(
                        previous,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                ))
            } else {
                // Older Android versions do not provide the atomic two-component API.
                pm.setComponentEnabledSetting(
                    chosen,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    previous,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
