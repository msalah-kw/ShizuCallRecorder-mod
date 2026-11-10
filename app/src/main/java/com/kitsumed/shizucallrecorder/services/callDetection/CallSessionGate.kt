/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.callDetection

/** Keeps independent detection sources from starting or stopping each other's single-call session. */
internal object CallSessionGate {
    private var owner: Any? = null

    @Synchronized
    fun acquire(candidate: Any): Boolean {
        if (owner != null && owner !== candidate) return false
        owner = candidate
        return true
    }

    @Synchronized
    fun release(candidate: Any): Boolean {
        if (owner !== candidate) return false
        owner = null
        return true
    }
}
