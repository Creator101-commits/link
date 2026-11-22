package com.link.clipboardsync

/**
 * Pure-JVM call state machine. Turns raw telephony transitions
 * (ringing / offhook / idle / contact-name) into the exact list of
 * call_state events to send to the Mac, deduplicated so the broadcast
 * receiver and the TelephonyCallback firing for the same transition
 * produce no extra sends.
 *
 * Not thread-safe — drive it from a single thread (the main handler).
 */
data class CallEvent(
    val state: String,          // "ringing" | "active" | "ended"
    val name: String?,
    val number: String?,
    val direction: String,      // "incoming" | "outgoing"
    val startedAt: Long?        // epoch ms, set only for "active"
)

class CallStateMachine {

    private enum class Phase { IDLE, RINGING, ACTIVE }

    private var phase = Phase.IDLE
    private var number: String? = null
    private var name: String? = null
    private var direction = "incoming"
    private var startedAt: Long? = null
    private var lastRingingSig: String? = null

    /** True while a call is ringing or active. */
    val isInCall: Boolean get() = phase != Phase.IDLE

    /** Current call direction ("incoming"/"outgoing") — for tests/diagnostics. */
    val currentDirection: String get() = direction

    /**
     * A ringing event. [num] may be null (the TelephonyCallback path carries
     * no number; the broadcast receiver does). Emits "ringing" on the first
     * ringing and again only when the number changes.
     */
    fun onRinging(num: String?): List<CallEvent> {
        val clean = num?.ifBlank { null }
        return when (phase) {
            Phase.IDLE -> {
                phase = Phase.RINGING
                direction = "incoming"
                number = clean
                name = null
                startedAt = null
                lastRingingSig = null
                emitRinging()
            }
            Phase.RINGING -> {
                if (clean != null && clean != number) {
                    number = clean
                    emitRinging()
                } else {
                    emptyList()
                }
            }
            Phase.ACTIVE -> emptyList()
        }
    }

    /**
     * Call went off-hook. From RINGING this answers an incoming call; from
     * IDLE it is an outgoing call (direction flips to "outgoing"). Emits
     * "active" exactly once per call.
     */
    fun onOffhook(nowMs: Long): List<CallEvent> {
        return when (phase) {
            Phase.RINGING -> {
                phase = Phase.ACTIVE
                startedAt = nowMs
                listOf(CallEvent("active", name, number, direction, startedAt))
            }
            Phase.IDLE -> {
                phase = Phase.ACTIVE
                direction = "outgoing"
                startedAt = nowMs
                listOf(CallEvent("active", name, number, direction, startedAt))
            }
            Phase.ACTIVE -> emptyList()
        }
    }

    /** Call ended / returned to idle. Emits "ended" once if ringing/active. */
    fun onIdle(): List<CallEvent> {
        return when (phase) {
            Phase.RINGING, Phase.ACTIVE -> {
                val ev = CallEvent("ended", name, number, direction, null)
                reset()
                listOf(ev)
            }
            Phase.IDLE -> emptyList()
        }
    }

    /**
     * Contact lookup resolved. Re-emits "ringing" (with the name) only if the
     * name/number signature changed since the last ringing emit.
     */
    fun onContactName(newName: String?): List<CallEvent> {
        name = newName?.ifBlank { null }
        return if (phase == Phase.RINGING) emitRingingIfChanged() else emptyList()
    }

    private fun emitRinging(): List<CallEvent> {
        lastRingingSig = ringingSig()
        return listOf(CallEvent("ringing", name, number, direction, null))
    }

    private fun emitRingingIfChanged(): List<CallEvent> {
        return if (ringingSig() != lastRingingSig) emitRinging() else emptyList()
    }

    private fun ringingSig(): String = "${name ?: ""}|${number ?: ""}"

    private fun reset() {
        phase = Phase.IDLE
        number = null
        name = null
        direction = "incoming"
        startedAt = null
        lastRingingSig = null
    }
}
