package com.link.clipboardsync

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Ref-counted Wi-Fi lock held only for the duration of file transfers whose
 * data path is LAN or the fast-link hotspot (never for Bluetooth relay).
 * Replaces the old always-on high-performance lock in the foreground service.
 */
object LinkWifiLock {
    private const val TAG = "LinkWifiLock"
    private val lock = Any()
    private var refs = 0
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire(ctx: Context) {
        synchronized(lock) {
            refs++
            if (refs == 1) {
                try {
                    val wm = ctx.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    wifiLock = wm.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "Link::TransferWifiLock"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not acquire WifiLock: ${e.message}")
                }
            }
        }
    }

    fun release() {
        synchronized(lock) {
            if (refs > 0) refs--
            if (refs == 0) {
                try {
                    wifiLock?.release()
                } catch (_: Exception) {
                }
                wifiLock = null
            }
        }
    }
}
