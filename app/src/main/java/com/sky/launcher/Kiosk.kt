package com.sky.launcher

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/** Режим киоска: работает, только если приложение назначено Device Owner через ADB. */
object Kiosk {
    fun enterIfOwner(act: Activity) {
        val dpm = act.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(act.packageName)) return
        val admin = ComponentName(act, AdminReceiver::class.java)
        val pkgs = act.packageManager.getInstalledApplications(0).map { it.packageName }.toTypedArray()
        dpm.setLockTaskPackages(admin, pkgs)
        dpm.addPersistentPreferredActivity(
            admin,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            },
            ComponentName(act, MainActivity::class.java)
        )
        act.startLockTask()
    }
}
