package com.link.clipboardsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/**
 * Phone-hotspot sharing (feature 22). On {"type":"hotspot_request"} the phone
 * checks whether its hotspot is set up / on, prompts the user via a
 * notification if needed, and reports {"type":"hotspot_status",...} as the AP
 * state changes. The Mac joins once we report "ready".
 */
object BridgeHotspotShare {
    private const val CHANNEL_ID = "hotspot_sharing"
    private const val CHANNEL_NAME = "Hotspot sharing"
    private const val NOTIF_ID = 9201
    private const val ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_WIFI_STATE = "wifi_state"
    private const val AP_STATE_ON = 13
    private const val AP_STATE_OFF = 11

    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    private var watchStartedAt = 0L
    private var wasReady = false
    private var watching = false

    /** Pure AP-state mapping: 13 -> on, 11 -> off, else null (ignored). */
    fun apState(state: Int): String? = when (state) {
        AP_STATE_ON -> "on"
        AP_STATE_OFF -> "off"
        else -> null
    }

    /** Current AP state via reflection (no permission needed), default off. */
    private fun isApOn(context: Context): Boolean {
        return runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val m = wm.javaClass.getMethod("isWifiApEnabled")
            m.invoke(wm) as? Boolean ?: false
        }.getOrDefault(false)
    }

    // Lazy so pure helpers (apState) work in JVM unit tests without a Looper.
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var timeoutRunnable: Runnable? = null

    fun handleControlMessage(context: Context, json: JSONObject) {
        // Called from the network read thread — hop to main for receiver/state.
        val type = json.optString("type")
        mainHandler.post {
            when (type) {
                "hotspot_request" -> onRequest(context)
                "hotspot_cancel" -> onCancel(context)
            }
        }
    }

    private fun onRequest(context: Context) {
        val app = context.applicationContext
        val name = BridgePrefs.getHotspotName(app)
        val password = BridgePrefs.getHotspotPassword(app)
        if (name.isNullOrEmpty() || password.isNullOrEmpty()) {
            postSetupNotification(app)
            sendStatus(app, "needs_setup")
            return
        }
        if (isApOn(app)) {
            sendReady(app)
            return
        }
        postTurnOnNotification(app)
        sendStatus(app, "prompted")
        startWatching(app)
    }

    private fun onCancel(context: Context) {
        stopWatching()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIF_ID)
    }

    // MARK: - Watching AP state

    private fun startWatching(context: Context) {
        if (watching) return
        watching = true
        wasReady = false
        watchStartedAt = System.currentTimeMillis()
        appContext = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(EXTRA_WIFI_STATE, -1)
                when (apState(state)) {
                    "on" -> {
                        if (!wasReady) {
                            wasReady = true
                            sendReady(ctx.applicationContext)
                        }
                    }
                    "off" -> {
                        if (wasReady) {
                            sendStatus(ctx.applicationContext, "off")
                            stopWatching()
                        }
                    }
                }
            }
        }
        receiver = r
        val filter = IntentFilter(ACTION_AP_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
        // 3-minute timeout if the hotspot never comes up.
        val timeout = Runnable {
            if (watching && !wasReady) {
                sendStatus(context.applicationContext, "timeout")
                stopWatching()
            }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, 180_000)
    }

    /** Called when the control link becomes ready again — re-send "ready" if
     *  we're watching and the AP is on (turning on hotspot can drop Wi-Fi). */
    fun onLinkReady(context: Context) {
        if (watching && (wasReady || isApOn(context))) {
            wasReady = true
            sendReady(context.applicationContext)
        }
    }

    fun stopWatching() {
        watching = false
        wasReady = false
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        receiver?.let { r -> appContext?.let { runCatching { it.unregisterReceiver(r) } } }
        receiver = null
        appContext = null
    }

    // MARK: - Send helpers

    private fun sendReady(context: Context) {
        val name = BridgePrefs.getHotspotName(context)
        val password = BridgePrefs.getHotspotPassword(context)
        val ssid = name ?: return
        val pass = password ?: return
        ClipboardSyncManager.networkClient?.sendRawJson(JSONObject().apply {
            put("type", "hotspot_status")
            put("state", "ready")
            put("ssid", ssid)
            put("password", pass)
        }.toString())
    }

    private fun sendStatus(context: Context, state: String) {
        ClipboardSyncManager.networkClient?.sendRawJson(JSONObject().apply {
            put("type", "hotspot_status")
            put("state", state)
        }.toString())
    }

    // MARK: - Notifications

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    private fun postSetupNotification(context: Context) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("open_tab", "settings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Set up hotspot sharing")
            .setContentText("Add your hotspot name and password in Bridge settings.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, n)
    }

    private fun postTurnOnNotification(context: Context) {
        ensureChannel(context)
        val intent = tetheringIntent(context)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Turn on hotspot for your Mac")
            .setContentText("Tap to open hotspot settings.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (MainActivity.isResumed) {
            val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
            if (!launched) nm.notify(NOTIF_ID, n)
        } else {
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun tetheringIntent(context: Context): Intent {
        val component = ComponentName(
            "com.android.settings", "com.android.settings.TetherSettings",
        )
        val resolved = context.packageManager.resolveActivity(
            Intent().setComponent(component), 0,
        )
        // Only use the TetherSettings component if it's actually exported.
        return if (resolved?.activityInfo?.exported == true) {
            Intent().setComponent(component)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
    }
}
