package com.kitsumed.shizucallrecorder.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.services.callDetection.CallDetectionMode
import com.kitsumed.shizucallrecorder.ui.screens.PermissionsContent
import com.kitsumed.shizucallrecorder.ui.screens.DisclaimerScreen
import com.kitsumed.shizucallrecorder.ui.common.ContactSelectionContent
import com.kitsumed.shizucallrecorder.ui.viewmodels.ContactEntry
import com.kitsumed.shizucallrecorder.ui.screens.SettingsScreen
import com.kitsumed.shizucallrecorder.ui.theme.ShizuCallRecorderTheme
import com.kitsumed.shizucallrecorder.ui.viewmodels.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en-w411dp-h891dp-420dpi")
class RecorderInterfaceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Application
    private lateinit var preferences: AppPreferences

    @Before
    fun preparePreferences() {
        app = RuntimeEnvironment.getApplication()
        preferences = AppPreferences(app)
        preferences.setAutoRecordIncomingEnabled(false)
        preferences.setAutoRecordOutgoingEnabled(false)
    }

    @Test
    fun navigationAndQuickSwitches_keepWorkingAcrossTheNewPages() {
        showSettings()
        compose.onNodeWithText(app.getString(R.string.general_incoming)).performClick()
        compose.runOnIdle { assertTrue(preferences.isAutoRecordIncomingEnabled()) }
        screenshot("01-home-light")
        compose.onNodeWithText(app.getString(R.string.ui_nav_calls)).performClick()
        compose.onNodeWithText(app.getString(R.string.settings_call_detection_method)).assertIsDisplayed()
        screenshot("02-calls-light")
        compose.onNodeWithText(app.getString(R.string.ui_nav_audio)).performClick()
        compose.onNodeWithText(app.getString(R.string.settings_audio_source)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.audio_bitrate_kbps, preferences.getAudioBitRate() / 1000)).assertIsDisplayed()
        screenshot("03-audio-light")
        compose.onNodeWithText(app.getString(R.string.general_settings)).performClick()
        compose.onNodeWithText(app.getString(R.string.settings_language)).assertIsDisplayed()
        screenshot("04-settings-light")
        compose.onNodeWithText(app.getString(R.string.ui_nav_home)).performClick()
        compose.onNodeWithText(app.getString(R.string.general_incoming)).assertIsOn()
    }

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h891dp-420dpi")
    fun arabicDarkInterface_rendersWithTheSameFunctionalNavigation() {
        showSettings(dark = true)
        compose.onNodeWithText(app.getString(R.string.ui_home_title)).assertIsDisplayed()
        screenshot("05-home-ar-dark")
        compose.onNodeWithText(app.getString(R.string.ui_nav_audio)).performClick()
        compose.onNodeWithText(app.getString(R.string.settings_audio_source)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.audio_bitrate_kbps, preferences.getAudioBitRate() / 1000)).assertIsDisplayed()
        screenshot("06-audio-ar-dark")
    }

    @Test
    @Config(qualifiers = "ar-rEG-w360dp-h740dp-320dpi")
    fun setupWithLargeArabicText_keepsTheGrantActionReachable() {
        var grants = 0
        val status = OnboardingStatus.Status(true, false, true, false, true, true, false, CallDetectionMode.InCallService, emptySet())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                ShizuCallRecorderTheme(darkTheme = true) {
                    PermissionsContent(status, { grants++ }, {}, false)
                }
            }
        }
        compose.onNodeWithText(app.getString(R.string.permissions_grant_access)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, grants) }
        screenshot("07-setup-ar-large")
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h800dp-mdpi")
    fun wideWindow_navigationRailOpensAudioSettings() {
        showSettings(dark = true)
        compose.onNodeWithText(app.getString(R.string.ui_nav_audio)).performClick()
        compose.onNodeWithText(app.getString(R.string.settings_audio_source)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.audio_bitrate_kbps, preferences.getAudioBitRate() / 1000)).assertIsDisplayed()
        screenshot("08-audio-tablet")
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h400dp-mdpi")
    fun shortLandscapeWindow_keepsAllDestinationsReachable() {
        showSettings()
        compose.onNodeWithText(app.getString(R.string.general_settings)).assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.settings_language)).assertIsDisplayed()
        screenshot("11-settings-landscape")
    }

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h891dp-420dpi")
    fun redesignedWelcome_keepsConsentRequired() {
        compose.setContent { ShizuCallRecorderTheme(darkTheme = true) { DisclaimerScreen(onContinue = {}) } }
        compose.onNodeWithTag("disclaimer-continue").assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.disclaimer_checkbox_label)).assertIsDisplayed()
        screenshot("09-welcome-ar-dark")
    }

    @Test
    fun redesignedContactPicker_searchKeepsTheExistingSelection() {
        val contacts = listOf(ContactEntry("one", "Mona", "+12025550101"), ContactEntry("two", "Omar", "+12025550102"))
        var selected = emptySet<String>()
        compose.setContent {
            ShizuCallRecorderTheme {
                ContactSelectionContent("Contacts", contacts, setOf("two"), { selected = it }, {})
            }
        }
        compose.onNodeWithText(app.getString(R.string.contact_search_hint)).performTextInput("Mona")
        compose.onNodeWithText("+12025550101").performClick()
        screenshot("10-contact-picker")
        compose.onNodeWithText(app.getString(R.string.general_ok)).performClick()
        compose.runOnIdle { assertEquals(setOf("one", "two"), selected) }
    }

    private fun showSettings(dark: Boolean = false) {
        val model = SettingsViewModel(app)
        compose.setContent { ShizuCallRecorderTheme(darkTheme = dark) { Surface { SettingsScreen(model) } } }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val destination = File("build/reports/ui/$name.png")
        destination.parentFile?.mkdirs()
        destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
