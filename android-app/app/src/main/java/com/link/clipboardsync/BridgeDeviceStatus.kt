package com.link.clipboardsync

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors

fun networkLabel(dataNetworkType: Int, overrideNetworkType: Int): String {
    val t = if (overrideNetworkType > 0) overrideNetworkType else dataNetworkType
    if (t < 0) return ""
    return when (t) {
        20 -> "5G"
        21 -> "5G+"
        19, 29 -> "LTE+"
        13 -> "LTE"
        3, 5, 6, 8, 10, 11, 12, 14, 15 -> "3G"
        1, 2, 16 -> "2G"
        else -> ""
    }
}

/**
 * Sends the phone's status (battery + cellular) to the Mac as
 * {"type":"device_status", ...} over the encrypted control channel.
 *
 * A full snapshot goes out whenever the control link authenticates; after
 * that only changed fields are sent. Signal-only changes are debounced to
 * at most one send per 10 s.
 */
object BridgeDeviceStatus {

    private const val SIGNAL_DEBOUNCE_MS = 10_000L
    private const val TAG = "BridgeDeviceStatus"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val telephonyExecutor = Executors.newSingleThreadExecutor()

    private var appContext: Context? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var legacyPhoneListener: PhoneStateListener? = null
    private var started = false

    // Latest known fields
    private var batteryLevel = -1
    private var charging = false
    private var plug: String? = null
    private var carrier: String? = null
    private var network = ""
    private var signal = -1
    private var noService = false
    private var airplane = false
    private var hasPhoneState = false

    private var lastSentJson: JSONObject? = null
    private var lastSentAt = 0L
    private var signalDeferredRunnable: Runnable? = null

    @Volatile private var linkReady = false

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext

        registerBatteryReceiver(context)
        refreshAirplane(context)
        if (hasPhoneStatePermission(context)) {
            hasPhoneState = true
            registerTelephony(context)
        }
    }

    /** Re-evaluate after runtime permission grant. */
    fun refresh(context: Context) {
        if (!started) { start(context); return }
        val hasPhone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
        if (hasPhone && telephonyManager == null) registerTelephony(context)
        else if (!hasPhone && telephonyManager != null) unregisterTelephony()
    }

    fun stop() {
        started = false
        linkReady = false
        appContext?.let { ctx ->
            batteryReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        }
        batteryReceiver = null
        unregisterTelephony()
        signalDeferredRunnable?.let { mainHandler.removeCallbacks(it) }
        signalDeferredRunnable = null
        lastSentJson = null
        appContext = null
    }

    /** Called by the service when the encrypted link authenticates. */
    fun onLinkReady() {
        linkReady = true
        // Always send a fresh snapshot on (re)connect.
        lastSentJson = null
        lastSentAt = 0L
        signalDeferredRunnable?.let { mainHandler.removeCallbacks(it) }
        signalDeferredRunnable = null
        sendSnapshotIfChanged(force = true)
    }

    fun onLinkLost() {
        linkReady = false
        signalDeferredRunnable?.let { mainHandler.removeCallbacks(it) }
        signalDeferredRunnable = null
    }

    // MARK: - Battery

    private fun registerBatteryReceiver(context: Context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val newLevel = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val newCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                val newPlug = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> null
                }
                if (newLevel != batteryLevel || newCharging != charging || newPlug != plug) {
                    batteryLevel = newLevel
                    charging = newCharging
                    plug = newPlug
                    sendSnapshotIfChanged()
                }
            }
        }
        // Sticky broadcast: registerReceiver(null, filter) returns current state.
        val sticky = context.registerReceiver(null, filter)
        sticky?.let { batteryReceiver?.onReceive(context, it) }
        context.registerReceiver(batteryReceiver, filter)
    }

    // MARK: - Cellular

    private fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

    private fun registerTelephony(context: Context) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        telephonyManager = tm

        refreshCarrierAndNetwork(context)
        refreshServiceState()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(),
                TelephonyCallback.DisplayInfoListener,
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.ServiceStateListener {

                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    refreshCarrierAndNetwork(context)
                }

                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    updateSignal(listOf(signalStrength))
                }

                override fun onServiceStateChanged(serviceState: ServiceState) {
                    updateServiceState(serviceState)
                }
            }
            telephonyCallback = cb
            runCatching {
                tm.registerTelephonyCallback(telephonyExecutor, cb)
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    updateSignal(listOf(signalStrength))
                }

                override fun onServiceStateChanged(serviceState: ServiceState) {
                    updateServiceState(serviceState)
                }
            }
            legacyPhoneListener = listener
            runCatching {
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                        PhoneStateListener.LISTEN_SERVICE_STATE)
            }
        }
    }

    private fun unregisterTelephony() {
        val tm = telephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { cb -> runCatching { tm?.unregisterTelephonyCallback(cb) } }
        } else {
            legacyPhoneListener?.let { l ->
                runCatching {
                    @Suppress("DEPRECATION")
                    tm?.listen(l, PhoneStateListener.LISTEN_NONE)
                }
            }
        }
        telephonyCallback = null
        legacyPhoneListener = null
        telephonyManager = null
    }

    private fun refreshCarrierAndNetwork(context: Context) {
        val tm = telephonyManager ?: return
        carrier = runCatching { tm.networkOperatorName?.ifBlank { null } }.getOrNull()
        network = networkLabel(dataNetworkType(), 0)
        signalChangedFields()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun dataNetworkType(): Int =
        runCatching { telephonyManager?.dataNetworkType ?: -1 }.getOrDefault(-1)

    @SuppressLint("MissingPermission")
    private fun refreshServiceState() {
        val tm = telephonyManager ?: return
        val ss = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // ServiceState via TelephonyCallback arrives async; seed best-effort.
                null
            } else null
        }.getOrNull()
        ss?.let { updateServiceState(it) }
    }

    private fun updateSignal(strengths: List<SignalStrength>) {
        if (strengths.isEmpty()) return
        // level is 0-4 (API 28+); fall back to asuLevel-based approximation.
        val lvl = strengths.maxOfOrNull { s ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) s.level else -1
        } ?: -1
        val newSignal = if (lvl >= 0) lvl else signalFromAsu(strengths)
        if (newSignal != signal) {
            signal = newSignal
            // Signal-only changes are debounced.
            scheduleSignalSend()
        }
    }

    private fun signalFromAsu(strengths: List<SignalStrength>): Int {
        // Rough mapping: typical LTE RSRP -44..-140 -> 4..0 bands.
        val asu = strengths.maxOfOrNull { it.level } ?: return -1
        return when {
            asu >= 4 -> 4
            asu == 3 -> 3
            asu == 2 -> 2
            asu == 1 -> 1
            else -> 0
        }
    }

    private fun updateServiceState(serviceState: ServiceState) {
        val inService = serviceState.state == ServiceState.STATE_IN_SERVICE
        val newNoService = !inService
        if (newNoService != noService) {
            noService = newNoService
            signalChangedFields()
        }
    }

    private fun refreshAirplane(context: Context) {
        airplane = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) != 0
        }.getOrDefault(false)
    }

    // MARK: - Sending

    private fun buildSnapshot(): JSONObject {
        val json = JSONObject()
        json.put("type", "device_status")
        if (batteryLevel >= 0) json.put("battery", batteryLevel)
        json.put("charging", charging)
        plug?.let { json.put("plug", it) }
        if (hasPhoneState) {
            carrier?.let { json.put("carrier", it) }
            if (network.isNotEmpty()) json.put("network", network)
            if (signal >= 0) json.put("signal", signal)
            json.put("noService", noService)
            json.put("airplane", airplane)
        }
        return json
    }

    private fun fieldsEqual(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return false
        val keysA = a.keys().asSequence().toSet()
        val keysB = b.keys().asSequence().toSet()
        if (keysA != keysB) return false
        for (k in keysA) {
            if (a.opt(k) != b.opt(k)) return false
        }
        return true
    }

    /** Send a snapshot if any field changed since last send. */
    private fun sendSnapshotIfChanged(force: Boolean = false) {
        if (!linkReady) return
        appContext ?: return
        refreshAirplane(appContext!!)
        val snap = buildSnapshot()
        if (!force && fieldsEqual(lastSentJson, snap)) return
        val client = ClipboardSyncManager.networkClient ?: return
        if (!client.isConnected()) return
        client.sendRawJson(snap.toString())
        lastSentJson = snap
        lastSentAt = System.currentTimeMillis()
    }

    /** Only the signal field changed — debounce to one send per 10 s. */
    private fun scheduleSignalSend() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastSentAt
        if (elapsed >= SIGNAL_DEBOUNCE_MS) {
            sendSnapshotIfChanged()
            return
        }
        signalDeferredRunnable?.let { mainHandler.removeCallbacks(it) }
        val wait = SIGNAL_DEBOUNCE_MS - elapsed
        val r = Runnable { signalDeferredRunnable = null; sendSnapshotIfChanged() }
        signalDeferredRunnable = r
        mainHandler.postDelayed(r, wait)
    }

    /** Called when any non-signal field may have changed (carrier/network/service). */
    private fun signalChangedFields() {
        sendSnapshotIfChanged()
    }

    // MARK: - Pure helpers (unit-testable)

}
