/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callDetection.incall

import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.RawCallData
import com.kitsumed.shizucallrecorder.services.RecordingDecisionEngine
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.utils.PhoneNumberManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.kitsumed.shizucallrecorder.services.callDetection.CallSessionGate
import com.kitsumed.shizucallrecorder.system.audio.AudioModeResolver

/**
 * InCallService implementation responsible for detecting call state changes and relaying them to the [RecordingDecisionEngine].
 * This is the primary call detection method for Android 12+ devices, leveraging the latest Telecom framework capabilities.
 *
 * Note: This service is designed to track only one call at a time. Parallel calls (e.g. call waiting, second incoming call) are not currently supported in this implementation.
 * We would first need to rework the whole recording logic.
 *
 * -- Support Guide for Maintainers --
 * This services works because Android 12+ added a single OR statement that checks for MANAGE_ONGOING_CALLS AppOps when InCallController
 * devices if it binds to our InCallService or not. We are binding as a companion/system-non-ui app, "IN_CALL_SERVICE_TYPE_NON_UI".
 * To ensure in future Android releases that they did not change this logic, we must check InCallController source code.
 * Android 11 (no AppOps, impossible to use): https://cs.android.com/android/platform/superproject/+/android11-release:packages/services/Telecomm/src/com/android/server/telecom/InCallController.java;l=1476-1486
 * Android 12 (works) : https://cs.android.com/android/platform/superproject/+/android12-release:packages/services/Telecomm/src/com/android/server/telecom/InCallController.java;l=1818-1829
 * Android 17 (works) : https://cs.android.com/android/platform/superproject/+/android-17.0.0_r1:packages/services/Telecomm/src/com/android/server/telecom/InCallController.java;l=2797-2809
 *
 * We can also quickly check that we are binding as NON_UI by running with adb "adb shell telecom is-non-ui-in-call-service-bound com.kitsumed.shizucallrecorder" while a call is ongoing.
 * For more detailed information, look at kitsumed comment: https://github.com/kitsumed/ShizuCallRecorder/issues/4#issuecomment-4524107187
 */
@RequiresApi(Build.VERSION_CODES.S) // Call detection method only available on Android 12+ for us. This hide warnings of previous API deprecations.
class InCallService : InCallService() {

    private lateinit var appPreferences: AppPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeTrackedCall: Call? = null
    private var decisionJob: Job? = null
    private var isPipelineExecuted = false

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = handleCallStateChanged(call, state)
        override fun onDetailsChanged(call: Call, details: Call.Details) = handleCallStateChanged(call, details.state)
    }

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(this)
    }

    override fun onDestroy() {
        releasePrimaryTrackedCall()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (activeTrackedCall != null) return
        activeTrackedCall = call
        call.registerCallback(callCallback)
        handleCallStateChanged(call, call.details.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (call === activeTrackedCall) releasePrimaryTrackedCall()
    }

    private fun handleCallStateChanged(call: Call, state: Int) {
        if (call !== activeTrackedCall) return
        AppLogger.d("Telecom call state: ${callStateToString(state)}")
        if (state == Call.STATE_DISCONNECTED) {
            releasePrimaryTrackedCall()
            return
        }
        val details = call.details
        // OEMs can deliver the account after ACTIVE. onDetailsChanged retries without guessing its identity.
        val packageName = details.accountHandle?.componentName?.packageName ?: return
        val telecom = getSystemService(TELECOM_SERVICE) as? TelecomManager
        val isCarrier = packageName == telecom?.systemDialerPackage ||
            packageName == telecom?.defaultDialerPackage || packageName == "com.android.phone"
        if (!isCarrier && !appPreferences.isRecordThirdPartyCallsEnabled()) return
        if (!CallSessionGate.acquire(call)) return
        if (state != Call.STATE_ACTIVE || isPipelineExecuted || decisionJob?.isActive == true) return

        decisionJob = serviceScope.launch {
            val isVoip = details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) ||
                details.hasProperty(Call.Details.PROPERTY_VOIP_AUDIO_MODE) ||
                details.hasProperty(Call.Details.PROPERTY_WIFI) ||
                AudioModeResolver(this@InCallService).isVoipAudioModeWithRetry()
            val rawData = RawCallData(
                rawPhoneNumber = PhoneNumberManager.normalisePhoneNumber(details.handle?.schemeSpecificPart ?: ""),
                direction = when (details.callDirection) {
                    Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING
                    Call.Details.DIRECTION_OUTGOING -> CallDirection.OUTGOING
                    else -> CallDirection.UNKNOWN
                },
                osProvidedCallerName = details.contactDisplayName ?: details.callerDisplayName,
                packageName = packageName,
                isCallAudioVoIpBased = isVoip
            )
            AppLogger.i("Call detected: source=Telecom package=$packageName voip=$isVoip")
            isPipelineExecuted = RecordingDecisionEngine.getInstance(this@InCallService).executeDecisionPipeline(rawData)
        }
    }

    private fun releasePrimaryTrackedCall() {
        val call = activeTrackedCall ?: return
        decisionJob?.cancel()
        decisionJob = null
        call.unregisterCallback(callCallback)
        if (CallSessionGate.release(call) && isPipelineExecuted) {
            RecordingDecisionEngine.getInstance(this).endRecordingSession()
        }
        isPipelineExecuted = false
        activeTrackedCall = null
    }

    /**
     * Converts a call state integer to a human-readable string for logging purposes.
     * @param state The call state integer (e.g., Call.STATE_ACTIVE).
     * @return A string representation of the call state.
     */
    private fun callStateToString (state: Int): String {
        return when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
            else -> "UNKNOWN_STATE($state)"
        }
    }
}