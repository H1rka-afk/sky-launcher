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
}
