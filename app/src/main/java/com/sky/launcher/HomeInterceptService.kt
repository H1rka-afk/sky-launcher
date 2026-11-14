package com.sky.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Режим без ADB: когда на экране появляется стандартный лаунчер
 * (после включения ТВ или по кнопке Home), сразу открываем Sky Launcher.
 */
class HomeInterceptService : AccessibilityService() {

    private var stockHomes: Set<String> = emptySet()

    override fun onServiceConnected() {
        stockHomes = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }
            .filter { it != packageName && it != "com.android.settings" }
            .toSet()
        openLauncher()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in stockHomes) openLauncher()
    }

    private fun openLauncher() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        )
    }

    override fun onInterrupt() {}
}
