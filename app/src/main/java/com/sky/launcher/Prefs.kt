package com.sky.launcher

import android.content.Context
import android.content.SharedPreferences

/** Настройки Sky OS (не телевизора). */
object Prefs {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) { if (!::p.isInitialized) p = ctx.applicationContext.getSharedPreferences("skyos_settings", Context.MODE_PRIVATE) }

    var motion: Boolean
        get() = p.getBoolean("motion", true)
        set(v) = p.edit().putBoolean("motion", v).apply()

    var labels: Boolean
        get() = p.getBoolean("labels", true)
        set(v) = p.edit().putBoolean("labels", v).apply()

    var autoUpdate: Boolean
        get() = p.getBoolean("auto_update", true)
        set(v) = p.edit().putBoolean("auto_update", v).apply()

    var lastUpdateCheck: Long
        get() = p.getLong("last_update_check", 0)
        set(v) = p.edit().putLong("last_update_check", v).apply()

    var layoutVersion: Int
        get() = p.getInt("layout_version", 0)
        set(v) = p.edit().putInt("layout_version", v).apply()

    val DEFAULT_TILES = listOf("wallpaper", "sky_settings", "input", "network", "timer", "tv_settings")

    var ccTiles: List<String>
        get() = p.getString("cc_tiles", null)?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_TILES
        set(v) = p.edit().putString("cc_tiles", v.joinToString(",")).apply()

    // ---- Экран блокировки ----
    var pinHash: String?
        get() = p.getString("pin_hash", null)
        set(v) = p.edit().putString("pin_hash", v).apply()

    var lockOnWake: Boolean
        get() = p.getBoolean("lock_on_wake", true)
        set(v) = p.edit().putBoolean("lock_on_wake", v).apply()

    /** Автоблокировка на главном экране, минут (0 — никогда). */
    var lockIdleMin: Int
        get() = p.getInt("lock_idle_min", 15)
        set(v) = p.edit().putInt("lock_idle_min", v).apply()

    var pinReminder: Boolean
        get() = p.getBoolean("pin_reminder", true)
        set(v) = p.edit().putBoolean("pin_reminder", v).apply()

    // ---- Уведомления ----
    var appNotifications: Boolean
        get() = p.getBoolean("app_notifications", true)
        set(v) = p.edit().putBoolean("app_notifications", v).apply()

    // ---- Sky Drop ----
    var dropEnabled: Boolean
        get() = p.getBoolean("drop_enabled", true)
        set(v) = p.edit().putBoolean("drop_enabled", v).apply()

    var dropAutoAccept: Boolean
        get() = p.getBoolean("drop_auto", false)
        set(v) = p.edit().putBoolean("drop_auto", v).apply()

    val dropKey: String
        get() = p.getString("drop_key", null) ?: (1..8).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
            .also { p.edit().putString("drop_key", it).apply() }
}
