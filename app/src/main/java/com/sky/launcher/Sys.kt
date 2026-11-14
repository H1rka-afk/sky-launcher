package com.sky.launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.StatFs

/** Доступ к системным функциям для Пункта управления и виджетов. */
object Sys {
    enum class Net { WIFI, ETHERNET, NONE }

    private fun wifi(ctx: Context) =
        ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun wifiOn(ctx: Context): Boolean =
        try { wifi(ctx)?.isWifiEnabled == true } catch (e: Exception) { false }

    @Suppress("DEPRECATION")
    fun setWifi(ctx: Context, on: Boolean): Boolean {
        return try { wifi(ctx)?.setWifiEnabled(on) == true } catch (e: Exception) { false }
    }

    private fun bt(ctx: Context): BluetoothAdapter? =
        try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter } catch (e: Exception) { null }

    fun hasBt(ctx: Context) = bt(ctx) != null

    @SuppressLint("MissingPermission")
    fun btOn(ctx: Context): Boolean =
        try { bt(ctx)?.isEnabled == true } catch (e: Exception) { false }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun setBt(ctx: Context, on: Boolean): Boolean {
        val a = bt(ctx) ?: return false
        return try { if (on) a.enable() else a.disable() } catch (e: Exception) { false }
    }

    @Suppress("DEPRECATION")
    fun net(ctx: Context): Net {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Net.NONE
        val ni = try { cm.activeNetworkInfo } catch (e: Exception) { null } ?: return Net.NONE
        if (!ni.isConnected) return Net.NONE
        return if (ni.type == ConnectivityManager.TYPE_ETHERNET) Net.ETHERNET else Net.WIFI
    }

    fun freeGb(): Double =
        try { StatFs(Environment.getDataDirectory().path).availableBytes / 1_000_000_000.0 } catch (e: Exception) { 0.0 }

    fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
