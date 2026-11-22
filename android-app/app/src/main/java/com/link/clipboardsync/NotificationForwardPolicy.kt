package com.link.clipboardsync

import android.app.NotificationManager

/**
 * Pure decision for whether an Android notification should be forwarded to
 * the Mac. Per-app "off" rules drop it before sending (saves Bluetooth);
 * skipSilent drops low-importance/ambient/alert-once-update notifications
 * unless they are calls or messaging-style with new messages.
 */
object NotificationForwardPolicy {

    /**
     * @param level per-app rule: "off"|"muted"|"quiet"|"normal"|"high"|null
     * @param importance Ranking.importance (or a sentinel < IMPORTANCE_DEFAULT
     *        for silent updates — see caller)
     * @param ambient Ranking.isAmbient
     * @param skipSilent user setting
     * @param isCallOrMessage call or messaging-style notification with new messages
     */
    fun shouldForward(
        level: String?,
        importance: Int,
        ambient: Boolean,
        skipSilent: Boolean,
        isCallOrMessage: Boolean
    ): Boolean {
        if (level == "off") return false
        if (!skipSilent || isCallOrMessage) return true
        val silent = importance < NotificationManager.IMPORTANCE_DEFAULT || ambient
        return !silent
    }
}
