package com.link.clipboardsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import org.json.JSONObject

/**
 * Handles {"type":"wifi_share"} from the Mac: validates, then adds the network
 * via the best available API and replies {"type":"wifi_share_ack",...}.
 * Pure validation lives in [WifiShareRequest.fromJson] (unit-tested).
 */
object BridgeWifiShare {
    private const val CHANNEL_ID = "wifi_sharing"
    private const val CHANNEL_NAME = "Wi-Fi sharing"

    data class WifiShareRequest(
        val ssid: String,
        val password: String,
        val security: String, // wpa2|wpa3|open|wep
    ) {
        companion object {
            /** Pure validator — returns null for anything unsupported/invalid. */
            fun fromJson(o: JSONObject): WifiShareRequest? {
                val ssid = o.optString("ssid", "")
                if (ssid.isEmpty() || ssid.toByteArray(Charsets.UTF_8).size > 32) return null
                val security = o.optString("security", "")
                val password = o.optString("password", "")
                return when (security) {
                    "wpa2", "wpa3" -> {
                        if (password.length !in 8..63) return null
                        WifiShareRequest(ssid, password, security)
                    }
                    "wep" -> {
                        if (password.isEmpty()) return null
                        WifiShareRequest(ssid, password, security)
                    }
                    "open" -> WifiShareRequest(ssid, "", "open")
                    else -> null
                }
            }
        }
    }

    fun handle(context: Context, json: JSONObject) {
        val req = WifiShareRequest.fromJson(json) ?: run {
            sendAck(context, ok = false, reason = "Unsupported network")
            return
        }
        val ok = try {
            when {
                Build.VERSION.SDK_INT >= 30 -> addViaSuggestions(context, req)
                Build.VERSION.SDK_INT == 29 -> addViaSuggestionsApi29(context, req)
                else -> addViaLegacy(context, req)
            }
        } catch (e: Exception) {
            false
        }
        sendAck(context, ok = ok, reason = if (ok) "" else "Couldn't add the network")
    }

    // API 30+: WifiNetworkSuggestion + ACTION_WIFI_ADD_NETWORKS.
    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("DEPRECATION")
    private fun addViaSuggestions(context: Context, req: WifiShareRequest): Boolean {
        if (req.security == "wep") return false // suggestions don't support WEP
        val s = WifiNetworkSuggestion.Builder()
            .setSsid(req.ssid)
        when (req.security) {
            "wpa2" -> s.setWpa2Passphrase(req.password)
            "wpa3" -> s.setWpa3Passphrase(req.password)
            // open: no passphrase
        }
        val suggestion = s.build()
        val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
            .putParcelableArrayListExtra(
                Settings.EXTRA_WIFI_NETWORK_LIST,
                arrayListOf(suggestion),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (MainActivity.isResumed) {
            context.startActivity(intent)
        } else {
            postJoinNotification(context, req.ssid, intent)
        }
        return true
    }

    // API 29: addNetworkSuggestions (system shows its own approval UI).
    @RequiresApi(Build.VERSION_CODES.Q)
    @Suppress("DEPRECATION")
    private fun addViaSuggestionsApi29(context: Context, req: WifiShareRequest): Boolean {
        if (req.security == "wep") return false
        val s = WifiNetworkSuggestion.Builder().setSsid(req.ssid)
        when (req.security) {
            "wpa2" -> s.setWpa2Passphrase(req.password)
            "wpa3" -> s.setWpa3Passphrase(req.password)
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.addNetworkSuggestions(listOf(s.build()))
        // Tell the user to approve the system's suggestion notification.
        val settingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        postJoinNotification(context, req.ssid, settingsIntent, approve = true)
        return true
    }

    // API 26-28: legacy WifiConfiguration.
    @Suppress("DEPRECATION")
    private fun addViaLegacy(context: Context, req: WifiShareRequest): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val config = WifiConfiguration().apply {
            SSID = "\"${req.ssid}\""
            when (req.security) {
                "wpa2", "wpa3" -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    preSharedKey = "\"${req.password}\""
                }
                "wep" -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    wepKeys[0] = "\"${req.password}\""
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                }
                else -> { // open
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
            }
        }
        val netId = wm.addNetwork(config)
        if (netId == -1) return false
        wm.disconnect()
        wm.enableNetwork(netId, false)
        wm.reconnect()
        return true
    }

    private fun postJoinNotification(
        context: Context,
        ssid: String,
        intent: Intent,
        approve: Boolean = false,
    ) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (approve) "Approve “$ssid”?" else "Join “$ssid”?"
        val text = if (approve)
            "Tap to approve the Wi-Fi network from your Mac."
        else
            "Tap to save the Wi-Fi network from your Mac."
        val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(9101, n)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    fun sendAck(context: Context, ok: Boolean, reason: String) {
        ClipboardSyncManager.networkClient?.sendRawJson(JSONObject().apply {
            put("type", "wifi_share_ack")
            put("ok", ok)
            if (reason.isNotEmpty()) put("reason", reason)
        }.toString())
    }
}
