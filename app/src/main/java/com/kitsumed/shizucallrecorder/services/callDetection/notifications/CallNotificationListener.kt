/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callDetection.notifications

import android.app.Notification
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.RawCallData
import com.kitsumed.shizucallrecorder.services.RecordingDecisionEngine
import com.kitsumed.shizucallrecorder.services.callDetection.CallSessionGate
import com.kitsumed.shizucallrecorder.system.audio.AudioModeResolver
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Opt-in fallback. Retains call metadata only; never reads notification text, people, or actions. */
class CallNotificationListener : NotificationListenerService() {
    private data class Candidate(val packageName: String, val ongoing: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val candidates = mutableMapOf<String, Candidate>()
    private val incomingPackages = mutableSetOf<String>()
    private val preferences by lazy { AppPreferences(this) }
    private val audioMode by lazy { AudioModeResolver(this) }
    private var monitor: Job? = null
    private var recordingPackage: String? = null
    private var pipelineSent = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (failure: SecurityException) {
            AppLogger.w("Notification access was revoked during listener setup.", failure)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!preferences.isNotificationCallFallbackEnabled() || !preferences.isRecordThirdPartyCallsEnabled()) return
        if (sbn.packageName !in SUPPORTED_PACKAGES) return
        val notification = sbn.notification
        if (notification.category != Notification.CATEGORY_CALL) {
            onNotificationRemoved(sbn)
            return
        }
        val callType = if (Build.VERSION.SDK_INT >= 34) notification.extras.getInt(Notification.EXTRA_CALL_TYPE) else 0
        if (Build.VERSION.SDK_INT >= 34 && callType == Notification.CallStyle.CALL_TYPE_INCOMING) {
            incomingPackages.add(sbn.packageName)
        }
        val ongoing = isOngoingCall(notification)
        if (!ongoing && callType == 0) {
            onNotificationRemoved(sbn)
            return
        }
        candidates[sbn.key] = Candidate(sbn.packageName, ongoing)
        if ((ongoing || recordingPackage != null) && monitor?.isActive != true) {
            monitor = scope.launch { monitorCalls() }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        candidates.remove(sbn.key)
        if (candidates.values.none { it.packageName == sbn.packageName } && recordingPackage != sbn.packageName) {
            incomingPackages.remove(sbn.packageName)
        }
    }

    private suspend fun monitorCalls() {
        // Give Telecom first chance to supply verified identity and direction.
        delay(1_000)
        var inactiveSince: Long? = null
        while (candidates.values.any { it.ongoing } || recordingPackage != null) {
            if (!preferences.isNotificationCallFallbackEnabled() || !preferences.isRecordThirdPartyCallsEnabled()) {
                endOwnedSession()
                return
            }
            val active = audioMode.isVoipAudioMode()
            val candidate = candidates.values.firstOrNull { it.ongoing && (recordingPackage == null || it.packageName == recordingPackage) }
            if (active && candidate != null) {
                inactiveSince = null
                if (recordingPackage == null && CallSessionGate.acquire(this)) {
                    recordingPackage = candidate.packageName
                    AppLogger.i("Call detected: source=notification package=${candidate.packageName} voip=true")
                    pipelineSent = RecordingDecisionEngine.getInstance(this).executeDecisionPipeline(
                        RawCallData(
                            rawPhoneNumber = "",
                            direction = if (candidate.packageName in incomingPackages) CallDirection.INCOMING else CallDirection.UNKNOWN,
                            packageName = candidate.packageName,
                            isCallAudioVoIpBased = true
                        )
                    )
                    if (!pipelineSent) {
                        endOwnedSession()
                        return
                    }
                }
            } else if (recordingPackage != null) {
                // Brief route changes or replacement notifications must not truncate a call.
                val now = SystemClock.elapsedRealtime()
                val since = inactiveSince ?: now.also { inactiveSince = it }
                if (now - since >= 2_000) {
                    val endedPackage = recordingPackage
                    endOwnedSession()
                    candidates.entries.removeAll { it.value.packageName == endedPackage }
                    inactiveSince = null
                }
            }
            delay(500)
        }
        incomingPackages.clear()
    }

    private fun endOwnedSession() {
        if (CallSessionGate.release(this) && pipelineSent) {
            RecordingDecisionEngine.getInstance(this).endRecordingSession()
        }
        recordingPackage = null
        pipelineSent = false
        incomingPackages.clear()
    }

    override fun onListenerDisconnected() {
        monitor?.cancel()
        candidates.clear()
        endOwnedSession()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        scope.cancel()
        endOwnedSession()
        super.onDestroy()
    }

    companion object {
        private val SUPPORTED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca")

        internal fun isOngoingCall(notification: Notification): Boolean {
            if (notification.category != Notification.CATEGORY_CALL) return false
            if (Build.VERSION.SDK_INT >= 34) {
                when (notification.extras.getInt(Notification.EXTRA_CALL_TYPE)) {
                    Notification.CallStyle.CALL_TYPE_ONGOING -> return true
                    Notification.CallStyle.CALL_TYPE_INCOMING, Notification.CallStyle.CALL_TYPE_SCREENING -> return false
                }
            }
            return notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        }
    }
}
