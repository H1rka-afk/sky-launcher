package com.sky.launcher

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager

/** События системы для Dynamic Island: Wi‑Fi, Bluetooth, заряд пульта. */
object IslandEvents {

    private const val BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val BT_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"


    private var wifiConnected: Boolean? = null
    private var registered = false
    private var appCtx: Context? = null
    private val batteryWarned = HashMap<String, Int>()

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

    /** Регистрируется один раз на всё приложение (вызывают и служба, и главный экран). */
    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        appCtx = app
        val f = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BT_BATTERY)
        }
        try { app.registerReceiver(receiver, f) } catch (e: Exception) { registered = false }
    }

    fun unregister(ctx: Context) {}
}
