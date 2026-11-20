// startLocalOnlyHotspot/close require location (<=32) or NEARBY_WIFI_DEVICES
// (33+); hasFastLinkPermission checks them before any Wi-Fi call.
@file:SuppressLint("MissingPermission")

package com.link.clipboardsync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * "Fast link" transport for file transfers when the control channel is on
 * Bluetooth (devices not on the same Wi-Fi).
 *
 * The phone runs an Android LocalOnlyHotspot (private, no internet), sends the
 * SSID/passphrase to the Mac over the encrypted control channel
 * (`fastlink_offer`), the Mac joins it with CoreWLAN and replies
 * `fastlink_ready` with its hotspot IPv4. Data sockets then go to that IP over
 * plain TCP; on `fastlink_end`/disconnect/timeout the hotspot closes and the
 * Mac rejoins its previous Wi-Fi.
 *
 * All state is guarded by [lock]; blocking callers wait on it. The psk is
 * never logged.
 */
object BridgeFastLink {
    private const val TAG = "BridgeFastLink"
    private const val FAILURE_COOLDOWN_MS = 60_000L

    enum class DataRoute { LAN, FAST_LINK, BLUETOOTH }
    private enum class State { IDLE, STARTING, WAITING_MAC, READY, FAILED }

    // java.lang.Object (not Any) so wait/notifyAll are available.
    private val lock = Object()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fastlink-scheduler").apply { isDaemon = true }
    }

    private var state = State.IDLE
    private var sessionId: String? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var macIp: String? = null
    private var refcount = 0
    private var pendingAcquires = 0
    private var failReason: String? = null
    private var sessionTimeoutFuture: ScheduledFuture<*>? = null
    private var idleEndFuture: ScheduledFuture<*>? = null

    /** Last time a session failed; routeFor/prewarm stay on Bluetooth for [FAILURE_COOLDOWN_MS]. */
    @Volatile private var lastFailureAtMs = 0L

    // MARK: - Capability checks

    private fun wifiManager(ctx: Context): WifiManager? =
        ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun isSupported(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return wifiManager(ctx) != null
    }

    /** Null when fast-link permissions/location are usable, else a short reason. */
    private fun permissionBlockReason(ctx: Context): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) null else "no_permission"
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fineGranted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!fineGranted) return "no_permission"
            // Pre-33 hotspot APIs derive the interface name from location.
            val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null || !LocationManagerCompat.isLocationEnabled(lm)) return "location_off"
            null
        } else {
            "unsupported"
        }
    }

    fun hasFastLinkPermissions(ctx: Context): Boolean =
        isSupported(ctx) && permissionBlockReason(ctx) == null

    fun routeFor(ctx: Context, controlHost: String?): DataRoute {
        val appCtx = ctx.applicationContext
        if (isInFailureCooldown(System.currentTimeMillis(), lastFailureAtMs)) {
            return DataRoute.BLUETOOTH
        }
        val supported = isSupported(appCtx) && permissionBlockReason(appCtx) == null
        return chooseDataRoute(controlHost, BridgePrefs.isFastLinkEnabled(appCtx), supported)
    }

    // MARK: - Pure helpers (unit tested)

    fun chooseDataRoute(
        controlHost: String?,
        fastLinkEnabled: Boolean,
        fastLinkSupported: Boolean
    ): DataRoute = when {
        controlHost != null && controlHost != BridgeTransport.BLUETOOTH_HOST -> DataRoute.LAN
        controlHost == BridgeTransport.BLUETOOTH_HOST && fastLinkEnabled && fastLinkSupported -> DataRoute.FAST_LINK
        else -> DataRoute.BLUETOOTH
    }

    /** Returns the Mac hotspot IPv4 only when [obj] is a ready message for [expectedSession]. */
    fun parseReady(obj: JSONObject, expectedSession: String): String? {
        if (obj.optString("session") != expectedSession) return null
        val ip = obj.optString("ip")
        return if (NetworkSyncClient.isUsableLanHost(ip)) ip else null
    }

    /** Removes one pair of surrounding double quotes (SSID/PSK come quoted). */
    fun stripQuotes(s: String?): String? {
        if (s == null) return null
        val t = s.trim()
        return if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            t.substring(1, t.length - 1)
        } else {
            t
        }
    }

    /** True while a recent failure should keep transfers on the Bluetooth relay. */
    fun isInFailureCooldown(nowMs: Long, lastFailureAtMs: Long, cooldownMs: Long = FAILURE_COOLDOWN_MS): Boolean =
        lastFailureAtMs > 0L && nowMs - lastFailureAtMs < cooldownMs

    /** Clears the failure cooldown (setting toggled on / permissions granted). */
    fun clearFailureCooldown() {
        lastFailureAtMs = 0L
    }

    /**
     * The fast-link data path failed after the session was READY: end the
     * hotspot session (Mac restores its Wi-Fi) and start the failure cooldown
     * so subsequent transfers go straight to the Bluetooth relay.
     */
    fun reportDataPathFailure() {
        lastFailureAtMs = System.currentTimeMillis()
        endSession()
    }

    // MARK: - Session lifecycle

    /** Kicks off hotspot startup if no session is active. Asynchronous. */
    fun ensureSession(ctx: Context) {
        val appCtx = ctx.applicationContext
        synchronized(lock) {
            if (state == State.STARTING || state == State.WAITING_MAC || state == State.READY) return
            state = State.STARTING
            sessionId = UUID.randomUUID().toString()
            macIp = null
            failReason = null
            refcount = 0
        }
        val blockReason = permissionBlockReason(appCtx)
        if (blockReason != null || !isSupported(appCtx)) {
            failSession(blockReason ?: "unsupported")
            return
        }
        val wm = wifiManager(appCtx)
        if (wm == null) {
            failSession("no_wifi")
            return
        }
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    onHotspotStarted(appCtx, reservation)
                }

                override fun onStopped() {
                    onHotspotStopped()
                }

                override fun onFailed(reason: Int) {
                    onHotspotFailed(reason)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "startLocalOnlyHotspot failed: ${e.message}")
            failSession("start_failed")
            return
        }
        scheduleSessionTimeout()
    }

    private fun scheduleSessionTimeout() {
        val sid = synchronized(lock) { sessionId } ?: return
        sessionTimeoutFuture?.cancel(false)
        sessionTimeoutFuture = scheduler.schedule({
            val expired = synchronized(lock) {
                if (sessionId == sid && (state == State.STARTING || state == State.WAITING_MAC)) {
                    state = State.FAILED
                    failReason = "timeout"
                    lastFailureAtMs = System.currentTimeMillis()
                    lock.notifyAll()
                    true
                } else false
            }
            if (expired) {
                Log.w(TAG, "Fast link session timed out")
                endSession()
            }
        }, 25, TimeUnit.SECONDS)
    }

    private fun onHotspotStarted(ctx: Context, reservation: WifiManager.LocalOnlyHotspotReservation) {
        val config = extractHotspotConfig(reservation)
        if (config == null) {
            try { reservation.close() } catch (_: Exception) {}
            failSession("no_config")
            return
        }
        val (ssid, psk, sid) = synchronized(lock) {
            if (state != State.STARTING) {
                // Stale callback from a previous session; drop it.
                Triple(null, null, null)
            } else {
                this.reservation = reservation
                state = State.WAITING_MAC
                lock.notifyAll()
                Triple(config.first, config.second, sessionId)
            }
        }
        if (sid == null) {
            try { reservation.close() } catch (_: Exception) {}
            return
        }
        val offer = JSONObject().apply {
            put("type", "fastlink_offer")
            put("session", sid)
            put("ssid", ssid)
            put("psk", psk)
        }
        // Never log the offer: it contains the passphrase.
        Log.d(TAG, "Fast link hotspot up; offering session $sid to Mac")
        ClipboardSyncManager.networkClient?.sendRawJson(offer.toString())
    }

    @Suppress("DEPRECATION")
    private fun extractHotspotConfig(r: WifiManager.LocalOnlyHotspotReservation): Pair<String, String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val conf = r.softApConfiguration ?: return null
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                conf.wifiSsid?.toString()
            } else {
                conf.ssid
            }
            val psk = conf.passphrase
            val s = stripQuotes(ssid)
            val p = stripQuotes(psk)
            if (s.isNullOrEmpty() || p.isNullOrEmpty()) null else s to p
        } else {
            val conf = r.wifiConfiguration ?: return null
            val s = stripQuotes(conf.SSID)
            val p = stripQuotes(conf.preSharedKey)
            if (s.isNullOrEmpty() || p.isNullOrEmpty()) null else s to p
        }
    }

    private fun onHotspotStopped() {
        synchronized(lock) {
            if (state == State.STARTING || state == State.WAITING_MAC || state == State.READY) {
                state = State.FAILED
                failReason = failReason ?: "stopped"
                lastFailureAtMs = System.currentTimeMillis()
                reservation = null
                lock.notifyAll()
            }
        }
    }

    private fun onHotspotFailed(reason: Int) {
        Log.w(TAG, "LocalOnlyHotspot failed: $reason")
        failSession("hotspot_failed")
    }

    private fun failSession(reason: String) {
        var sid: String? = null
        synchronized(lock) {
            if (state == State.IDLE) return
            state = State.FAILED
            failReason = reason
            lastFailureAtMs = System.currentTimeMillis()
            sid = sessionId
            lock.notifyAll()
        }
        Log.w(TAG, "Fast link failed: $reason")
        sendFastLinkEnd(sid)
        val res = synchronized(lock) { reservation.also { reservation = null } }
        try { res?.close() } catch (_: Exception) {}
    }

    private fun sendFastLinkEnd(sid: String?) {
        if (sid == null) return
        val client = ClipboardSyncManager.networkClient ?: return
        if (!client.isConnected()) return
        val end = JSONObject().apply {
            put("type", "fastlink_end")
            put("session", sid)
        }
        client.sendRawJson(end.toString())
    }

    /** Tears down the hotspot and resets to IDLE. Safe to call anytime. */
    fun endSession() {
        val (res, sid) = synchronized(lock) {
            val r = reservation
            val s = sessionId
            state = State.IDLE
            sessionId = null
            macIp = null
            refcount = 0
            failReason = null
            reservation = null
            idleEndFuture?.cancel(false)
            idleEndFuture = null
            lock.notifyAll()
            r to s
        }
        sessionTimeoutFuture?.cancel(false)
        sessionTimeoutFuture = null
        sendFastLinkEnd(sid)
        try { res?.close() } catch (_: Exception) {}
    }

    fun onControlDisconnected() {
        endSession()
    }

    fun isActive(): Boolean = synchronized(lock) { state != State.IDLE }

    fun currentMacIp(): String? = synchronized(lock) { macIp }

    // MARK: - Data host acquisition (called from transfer worker threads)

    /**
     * Returns the host the data socket should dial: the LAN control host, the
     * [BridgeTransport.BLUETOOTH_HOST] sentinel, or the Mac's hotspot IPv4.
     * Blocks while the fast-link session comes up; falls back to Bluetooth on
     * failure or timeout.
     */
    fun acquireDataHost(ctx: Context, controlHost: String, timeoutMs: Long = 25000): String {
        val appCtx = ctx.applicationContext
        when (routeFor(appCtx, controlHost)) {
            DataRoute.LAN -> return controlHost
            DataRoute.BLUETOOTH -> return BridgeTransport.BLUETOOTH_HOST
            DataRoute.FAST_LINK -> {}
        }

        ensureSession(appCtx)
        val deadline = System.currentTimeMillis() + timeoutMs
        return synchronized(lock) {
            pendingAcquires++
            try {
                while (state != State.READY && state != State.FAILED && state != State.IDLE) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    try {
                        lock.wait(remaining)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                val ip = macIp
                if (state == State.READY && ip != null) {
                    refcount++
                    ip
                } else {
                    if (state == State.FAILED) {
                        Log.w(TAG, "fast link unavailable: ${failReason ?: "failed"}")
                    } else {
                        Log.w(TAG, "fast link unavailable: ${if (state == State.IDLE) "ended" else "timeout"}")
                    }
                    BridgeTransport.BLUETOOTH_HOST
                }
            } finally {
                pendingAcquires--
            }
        }
    }

    /** Releases a fast-link lease; schedules hotspot teardown after 15s idle. */
    fun releaseDataHost(host: String) {
        synchronized(lock) {
            if (host.isNotEmpty() && host == macIp) {
                refcount = maxOf(0, refcount - 1)
                if (refcount == 0) scheduleIdleEndLocked(15_000)
            }
        }
    }

    private fun scheduleIdleEndLocked(delayMs: Long) {
        idleEndFuture?.cancel(false)
        idleEndFuture = scheduler.schedule({
            val shouldEnd = synchronized(lock) {
                refcount == 0 && pendingAcquires == 0 && state != State.IDLE
            }
            if (shouldEnd) endSession()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    // MARK: - Control messages & prewarm

    fun handleControlMessage(ctx: Context, obj: JSONObject) {
        when (obj.optString("type")) {
            "fastlink_ready" -> {
                val expected = synchronized(lock) { sessionId } ?: return
                val ip = parseReady(obj, expected)
                if (ip == null) {
                    if (obj.optString("session") == expected) {
                        val reason = "bad_ready"
                        synchronized(lock) {
                            if (sessionId == expected && state != State.IDLE) {
                                state = State.FAILED
                                failReason = reason
                                lastFailureAtMs = System.currentTimeMillis()
                                lock.notifyAll()
                            }
                        }
                        endSession()
                    }
                    return
                }
                synchronized(lock) {
                    if (sessionId == expected && state == State.WAITING_MAC) {
                        macIp = ip
                        state = State.READY
                        Log.d(TAG, "Fast link ready: $ip")
                        lock.notifyAll()
                    }
                }
            }
            "fastlink_failed" -> {
                val sid = obj.optString("session")
                val reason = obj.optString("reason", "failed")
                val matched = synchronized(lock) {
                    if (sessionId != null && sid == sessionId && state != State.IDLE) {
                        failReason = reason
                        state = State.FAILED
                        lastFailureAtMs = System.currentTimeMillis()
                        lock.notifyAll()
                        true
                    } else false
                }
                if (matched) {
                    Log.w(TAG, "Mac reported fast link failure: $reason")
                    endSession()
                }
            }
            "fastlink_request" -> prewarm(ctx)
        }
    }

    /** Best-effort hotspot warm-up when a transfer is likely over Bluetooth. */
    fun prewarm(ctx: Context) {
        val appCtx = ctx.applicationContext
        val host = ClipboardSyncManager.networkClient?.getCurrentHost()
        if (isInFailureCooldown(System.currentTimeMillis(), lastFailureAtMs)) return
        val supported = isSupported(appCtx) && permissionBlockReason(appCtx) == null
        if (chooseDataRoute(host, BridgePrefs.isFastLinkEnabled(appCtx), supported) != DataRoute.FAST_LINK) return
        ensureSession(appCtx)
        // End the hotspot if no transfer acquires it within 20s.
        synchronized(lock) {
            idleEndFuture?.cancel(false)
            idleEndFuture = scheduler.schedule({
                val shouldEnd = synchronized(lock) {
                    refcount == 0 && pendingAcquires == 0 && state != State.IDLE
                }
                if (shouldEnd) endSession()
            }, 20, TimeUnit.SECONDS)
        }
    }
}
