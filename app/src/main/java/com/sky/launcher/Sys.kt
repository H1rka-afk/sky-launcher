package com.sky.launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
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

    class WifiNet(val ssid: String, val level: Int, val secure: Boolean, val caps: String)

    @Suppress("DEPRECATION")
    fun wifiScan(ctx: Context, startScan: Boolean = true): List<WifiNet> {
        val wm = wifi(ctx) ?: return emptyList()
        if (startScan) try { wm.startScan() } catch (e: Exception) {}
        val res = try { wm.scanResults ?: emptyList() } catch (e: Exception) { emptyList() }
        return res.filter { !it.SSID.isNullOrBlank() }
            .groupBy { it.SSID }
            .map { (ssid, list) ->
                val best = list.maxByOrNull { it.level }!!
                val caps = best.capabilities ?: ""
                WifiNet(ssid, best.level, listOf("WEP", "WPA", "SAE", "PSK", "EAP").any { caps.contains(it) }, caps)
            }
            .sortedByDescending { it.level }
    }

    @Suppress("DEPRECATION")
    fun currentSsid(ctx: Context): String? {
        val s = try { wifi(ctx)?.connectionInfo?.ssid } catch (e: Exception) { null } ?: return null
        val t = s.trim('"')
        return if (t.isBlank() || t == "<unknown ssid>" || t == "0x") null else t
    }

    @Suppress("DEPRECATION")
    fun isKnownWifi(ctx: Context, ssid: String): Boolean = try {
        wifi(ctx)?.configuredNetworks?.any { it.SSID == "\"$ssid\"" } == true
    } catch (e: Exception) { false }

    /** Подключение к сети (работает, т.к. targetSdk = 28). */
    @Suppress("DEPRECATION")
    fun connectWifi(ctx: Context, net: WifiNet, password: String?): Boolean {
        val wm = wifi(ctx) ?: return false
        return try {
            val quoted = "\"${net.ssid}\""
            val existing = try { wm.configuredNetworks?.firstOrNull { it.SSID == quoted } } catch (e: Exception) { null }
            val id = if (existing != null && password == null) existing.networkId else {
                val conf = WifiConfiguration().apply {
                    SSID = quoted
                    when {
                        password == null -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        net.caps.contains("WEP") -> {
                            wepKeys[0] = "\"$password\""; wepTxKeyIndex = 0
                            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                        }
                        else -> preSharedKey = "\"$password\""
                    }
                }
                existing?.let { wm.removeNetwork(it.networkId) }
                wm.addNetwork(conf)
            }
            if (id == -1) return false
            wm.disconnect()
            val ok = wm.enableNetwork(id, true)
            wm.reconnect()
            ok
        } catch (e: Exception) { false }
    }

    fun freeGb(): Double =
        try { StatFs(Environment.getDataDirectory().path).availableBytes / 1_000_000_000.0 } catch (e: Exception) { 0.0 }

    fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
