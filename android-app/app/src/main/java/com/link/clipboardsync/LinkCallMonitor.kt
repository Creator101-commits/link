package com.link.clipboardsync

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Monitors telephony calls and mirrors them to the Mac as
 * {"type":"call_state", ...}; accepts {"type":"call_action", ...} from the Mac.
 *
 * All telephony callbacks (broadcast receiver + TelephonyCallback/
 * PhoneStateListener) are funnelled onto the main thread where a single
 * [CallStateMachine] produces deduplicated [CallEvent]s. Contact-name lookup
 * runs on a background executor and posts its result back to the main thread.
 *
 * Requires READ_PHONE_STATE for call state; READ_CALL_LOG for the incoming
 * number; READ_CONTACTS for the caller name; ANSWER_PHONE_CALLS for actions.
 */
object LinkCallMonitor {

    private const val TAG = "LinkCallMonitor"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lookupExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false

    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var legacyPhoneListener: PhoneStateListener? = null
    private var callStateReceiver: BroadcastReceiver? = null

    // Call state machine — only touched on the main thread.
    private val machine = CallStateMachine()

    /** True while a call is ringing/active — used to suppress dialer notif_post. */
    @Volatile var isCallInProgress: Boolean = false
        private set

    fun hasPermissions(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

    fun start(context: Context) {
        if (started) return
        if (!hasPermissions(context)) return
        started = true
        appContext = context.applicationContext
        registerCallStateReceiver(context)
        registerCallStateListener(context)
    }

    /** Re-check permissions (e.g. after runtime grant) and start if possible. */
    fun refresh(context: Context) {
        if (!started && hasPermissions(context)) start(context)
    }

    fun stop() {
        started = false
        isCallInProgress = false
        appContext?.let { ctx ->
            callStateReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        }
        callStateReceiver = null
        unregisterListener()
        mainHandler.removeCallbacksAndMessages(null)
        appContext = null
    }

    // MARK: - Listeners (all funnel to the main-thread machine)

    private fun registerCallStateReceiver(context: Context) {
        // ACTION_PHONE_STATE_CHANGED carries EXTRA_INCOMING_NUMBER (needs READ_CALL_LOG).
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        callStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING ->
                        post { onMachineRinging(number) }
                    TelephonyManager.EXTRA_STATE_OFFHOOK ->
                        post { onMachineOffhook() }
                    TelephonyManager.EXTRA_STATE_IDLE ->
                        post { onMachineIdle() }
                }
            }
        }
        runCatching { context.registerReceiver(callStateReceiver, filter) }
    }

    private fun registerCallStateListener(context: Context) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        telephonyManager = tm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING -> post { onMachineRinging(null) }
                        TelephonyManager.CALL_STATE_OFFHOOK -> post { onMachineOffhook() }
                        TelephonyManager.CALL_STATE_IDLE -> post { onMachineIdle() }
                    }
                }
            }
            telephonyCallback = cb
            runCatching { tm.registerTelephonyCallback(lookupExecutor, cb) }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("deprecated")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING ->
                            post { onMachineRinging(phoneNumber) }
                        TelephonyManager.CALL_STATE_OFFHOOK -> post { onMachineOffhook() }
                        TelephonyManager.CALL_STATE_IDLE -> post { onMachineIdle() }
                    }
                }
            }
            legacyPhoneListener = listener
            runCatching {
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        }
    }

    private fun unregisterListener() {
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

    private fun post(block: () -> Unit) {
        mainHandler.post { block() }
    }

    // MARK: - Machine drivers (main thread only)

    private fun onMachineRinging(number: String?) {
        val events = machine.onRinging(number)
        dispatch(events)
        // Kick off contact lookup whenever we have a number and are still ringing.
        val clean = number?.ifBlank { null }
        if (clean != null && machine.isInCall) lookupContactName(clean)
    }

    private fun onMachineOffhook() {
        dispatch(machine.onOffhook(System.currentTimeMillis()))
    }

    private fun onMachineIdle() {
        dispatch(machine.onIdle())
        isCallInProgress = machine.isInCall
    }

    private fun onMachineContactName(name: String?) {
        dispatch(machine.onContactName(name))
    }

    private fun dispatch(events: List<CallEvent>) {
        isCallInProgress = machine.isInCall
        for (ev in events) sendCallEvent(ev)
    }

    // MARK: - Contact lookup (background executor -> main thread)

    private fun lookupContactName(number: String) {
        val ctx = appContext ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return
        lookupExecutor.execute {
            val name = runCatching {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
                )
                ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            post { onMachineContactName(name) }
        }
    }

    // MARK: - Sending

    private fun sendCallEvent(ev: CallEvent) {
        val ctx = appContext ?: return
        val client = ClipboardSyncManager.networkClient ?: return
        if (!client.isConnected()) return
        val json = JSONObject().apply {
            put("type", "call_state")
            put("state", ev.state)
            ev.name?.let { put("name", it) }
            ev.number?.let { put("number", it) }
            put("direction", ev.direction)
            ev.startedAt?.let { put("startedAt", it) }
        }
        client.sendRawJson(json.toString())
    }

    // MARK: - Inbound call_action from Mac

    fun handleControlMessage(context: Context, json: JSONObject) {
        if (json.optString("type") != "call_action") return
        val action = json.optString("action")
        when (action) {
            "answer" -> answerCall(context)
            "decline" -> declineCall(context)
            "end" -> endCall(context)
        }
    }

    private fun hasAnswerPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun telecomManager(context: Context): TelecomManager? =
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    @SuppressLint("MissingPermission")
    private fun answerCall(context: Context) {
        if (!hasAnswerPermission(context)) return
        runCatching {
            telecomManager(context)?.acceptRingingCall()
        }
    }

    private fun declineCall(context: Context) {
        endCall(context)
    }

    @SuppressLint("MissingPermission")
    private fun endCall(context: Context) {
        if (!hasAnswerPermission(context)) return
        // TelecomManager.endCall requires API 28; below that there is no
        // public end-call API, so ending from the Mac is a no-op on API 26-27.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            telecomManager(context)?.endCall()
        }
    }
}
