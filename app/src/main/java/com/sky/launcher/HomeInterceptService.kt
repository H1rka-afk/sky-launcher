package com.sky.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Служба Sky OS:
 *  • открывает Sky OS вместо стандартного лаунчера (после включения и по кнопке Home);
 *  • показывает экран блокировки при включении экрана;
 *  • показывает уведомления приложений в стиле iOS;
 *  • выполняет «Сон» и «Снимок экрана» из Пункта управления.
 */
class HomeInterceptService : AccessibilityService() {

    companion object { var instance: HomeInterceptService? = null }

    private var stockHomes: Set<String> = emptySet()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON && Prefs.lockOnWake) Lock.lockNow(this@HomeInterceptService)
        }
    }

    override fun onServiceConnected() {
        instance = this
        Prefs.init(this)
        stockHomes = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }
            .filter { it != packageName && it != "com.android.settings" && it != "com.android.tv.settings" }
            .toSet()
        try { registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON)) } catch (e: Exception) {}
        IslandEvents.register(this)
        SkyDrop.start(this)
        openLauncher()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg in stockHomes) openLauncher()
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> onNotification(event)
        }
    }

    /** Уведомление другого приложения → баннер Sky OS. */
    private fun onNotification(event: AccessibilityEvent) {
        if (!Prefs.appNotifications) return
        val n = event.parcelableData as? Notification ?: return    // тосты пропускаем
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        val ex = n.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: event.text.joinToString(" ")
        if (title.isBlank() && text.isBlank()) return
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        val icon = try {
            IconFactory.get(this, AppEntry(pkg, label, Intent(), packageManager.getApplicationIcon(pkg)), dpi(42))
        } catch (e: Exception) { null }
        Notify.post(this, title.ifBlank { label }, text, pkg, label, icon)
    }

    private fun openLauncher() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        )
    }

    /** Кнопки громкости: громкость меняем сами и показываем в островке вместо полоски телевизора. */
    override fun onKeyEvent(event: KeyEvent): Boolean = IslandEvents.volumeKey(this, event)

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        IslandEvents.unregister(this)
        instance = null
        super.onDestroy()
    }
}
