package com.kitsumed.shizucallrecorder.ui

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.services.callDetection.CallDetectionMode
import com.kitsumed.shizucallrecorder.ui.viewmodels.PermissionsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionsViewModelTest {
    @Test
    fun completedSetup_refreshesWithoutRequestingAnotherPermission() {
        val app = RuntimeEnvironment.getApplication()
        val mode = CallDetectionMode.InCallService
        val status = OnboardingStatus.Status(true, true, true, true, true, true, true, mode, mode.requiredPermissions)
        var refreshes = 0
        PermissionsViewModel(app).onGrantAccess(status, { fail("Unexpected permission request: $it") }, { fail("Unexpected folder picker") }, { refreshes++ })
        assertEquals(1, refreshes)
    }

    @Test
    fun switchingDetectionMode_updatesEnabledComponentsImmediately() {
        val app = RuntimeEnvironment.getApplication()
        val model = PermissionsViewModel(app)
        for (selected in listOf(CallDetectionMode.PhoneState, CallDetectionMode.InCallService)) {
            model.onCallDetectionModeChanged(selected)
            for (mode in CallDetectionMode.entries) {
                val expected = if (mode == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                assertEquals(expected, app.packageManager.getComponentEnabledSetting(ComponentName(app, mode.componentClassName)))
            }
        }
    }
}
