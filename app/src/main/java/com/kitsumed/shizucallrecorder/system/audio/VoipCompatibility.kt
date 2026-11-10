/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.system.audio

import com.kitsumed.shizucallrecorder.data.recording.CapturePlan
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager

/** Permission checks are prerequisites, not proof that the device exposes both sides of a call. */
data class VoipCompatibility(
    val playbackSupported: Boolean,
    val shizukuAuthorized: Boolean,
    val missingPermissions: List<String>
) {
    val prerequisitesMet: Boolean get() = playbackSupported && shizukuAuthorized && missingPermissions.isEmpty()

    companion object {
        private val PERMISSIONS = listOf(
            "android.permission.CAPTURE_AUDIO_OUTPUT",
            "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT",
            "android.permission.MODIFY_AUDIO_ROUTING"
        )

        fun inspect(): VoipCompatibility {
            val authorized = ShizukuConnectionManager.isAvailable() && ShizukuConnectionManager.hasPermission()
            return VoipCompatibility(
                CapturePlan.AudioPlayback.isSupportedForAndroidVersion(),
                authorized,
                if (authorized) PERMISSIONS.filterNot { ShizukuConnectionManager.checkServerPermission(it) } else emptyList()
            )
        }
    }
}
