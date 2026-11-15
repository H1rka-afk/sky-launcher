package com.sky.launcher

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.view.KeyEvent

/** События системы для Dynamic Island: громкость, Wi‑Fi, Bluetooth, заряд пульта. */
object IslandEvents {

    private const val BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val BT_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    private var wifiConnected: Boolean? = null
    private val batteryWarned = HashMap<String, Int>()

    /** Громкость: true — нажатие обработано Sky OS (полоска телевизора не появится). */
    fun volumeKey(ctx: Context, e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_MUTE) return false
        Prefs.init(ctx)
        if (!Prefs.island || !Prefs.islandVolume) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        // громкость управляется саундбаром/ресивером (HDMI-CEC) — пусть работает как обычно
        if (am.isVolumeFixed) return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_VOLUME_MUTE && e.repeatCount > 0) return true
            val dir = when (code) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                else -> AudioManager.ADJUST_TOGGLE_MUTE
            }
            try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) } catch (ex: Exception) { return false }
            showVolume(am)
        }
        return true
    }

    fun showVolume(am: AudioManager) {
        val s = AudioManager.STREAM_MUSIC
        Island.volume(am.getStreamVolume(s), am.getStreamMaxVolume(s), am.isStreamMute(s))
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!Prefs.island) return
            when (i.action) {
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val info = i.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO) ?: return
                    @Suppress("DEPRECATION")
                    val now = info.isConnected
                    val was = wifiConnected
                    wifiConnected = now
                    if (isInitialStickyBroadcast || was == null || was == now) return
                    if (now) {
                        @Suppress("DEPRECATION")
                        val ssid = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                            ?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                        Island.message("Wi‑Fi подключён", ssid, R.drawable.ic_wifi, 0xFF0A84FF.toInt())
                    } else Island.message("Wi‑Fi отключён", null, R.drawable.ic_wifi, 0xFF8E8E93.toInt())
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val name = try { d?.name } catch (e: Exception) { null } ?: "Устройство Bluetooth"
                    val on = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                    Island.message(if (on) "Подключено" else "Отключено", name, R.drawable.ic_bt,
                        if (on) 0xFF0A84FF.toInt() else 0xFF8E8E93.toInt())
                }
                BT_BATTERY -> {
                    val level = i.getIntExtra(BT_BATTERY_LEVEL, -1)
                    if (level < 0) return
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val addr = try { d?.address } catch (e: Exception) { null } ?: "?"
                    val name = try { d?.name } catch (e: Exception) { null } ?: "Пульт"
                    // предупреждаем один раз при 20% и один раз при 10%
                    val step = when { level <= 10 -> 10; level <= 20 -> 20; else -> 100 }
                    val prev = batteryWarned[addr] ?: 100
                    batteryWarned[addr] = step
                    if (step < prev) Island.message("$name: заряд $level%", "Скоро нужно зарядить или поменять батарейки",
                        R.drawable.ic_bt, if (level <= 10) 0xFFFF453A.toInt() else 0xFFFFD60A.toInt(), 5000)
                }
            }
        }
    }

    fun register(ctx: Context) {
        val f = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BT_BATTERY)
        }
        try { ctx.registerReceiver(receiver, f) } catch (e: Exception) {}
    }

    fun unregister(ctx: Context) { try { ctx.unregisterReceiver(receiver) } catch (e: Exception) {} }
}
