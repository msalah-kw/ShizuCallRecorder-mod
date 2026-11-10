package com.kitsumed.shizucallrecorder.services.callDetection

import android.app.Application
import android.app.Notification
import android.media.AudioManager
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.services.RecordingDecisionEngine
import com.kitsumed.shizucallrecorder.services.callDetection.notifications.CallNotificationListener
import com.kitsumed.shizucallrecorder.services.recording.RecordingForegroundService
import com.kitsumed.shizucallrecorder.utils.PhoneNumberManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallNotificationListenerTest {
    private lateinit var app: Application
    private lateinit var preferences: AppPreferences
    private lateinit var audio: AudioManager

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(RecordingDecisionEngine::class.java, "INSTANCE", null)
        ReflectionHelpers.setStaticField(PhoneNumberManager::class.java, "INSTANCE", null)
        preferences = AppPreferences(app)
        preferences.setNotificationCallFallbackEnabled(true)
        preferences.setRecordThirdPartyCallsEnabled(true)
        preferences.setAutoRecordIncomingEnabled(true)
        preferences.setAutoRecordOutgoingEnabled(true)
        preferences.setIgnoreAnonymousIncomingEnabled(false)
        preferences.setVibrationEnabled(false)
        audio = app.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    @Test
    fun ongoingNotification_startsOnceAndStopsAfterCallEnds() {
        val controller = Robolectric.buildService(CallNotificationListener::class.java).create()
        audio = controller.get().getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val listener = controller.get()
        val call = notification()
        try {
            listener.onNotificationPosted(call)
            advance(1_500)
            assertEquals(RecordingForegroundService.ACTION_START_RECORDING, awaitServiceAction())
            listener.onNotificationPosted(call)
            advance(1_000)
            assertNull(shadowOf(app).nextStartedService)
            listener.onNotificationRemoved(call)
            audio.mode = AudioManager.MODE_NORMAL
            advance(3_000)
            assertEquals(RecordingForegroundService.ACTION_STOP_RECORDING, awaitServiceAction())
        } finally { controller.destroy() }
    }

    @Test
    fun anotherDetectionSource_ownsItsStartAndStop() {
        val telecomCall = Any()
        assertTrue(CallSessionGate.acquire(telecomCall))
        val controller = Robolectric.buildService(CallNotificationListener::class.java).create()
        audio = controller.get().getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        try {
            controller.get().onNotificationPosted(notification())
            advance(1_500)
            controller.get().onListenerDisconnected()
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(CallSessionGate.release(telecomCall))
        } finally {
            CallSessionGate.release(telecomCall)
            controller.destroy()
        }
    }

    @Test
    fun nextAppCallArrivingDuringStopGrace_isNotLost() {
        val controller = Robolectric.buildService(CallNotificationListener::class.java).create()
        val listener = controller.get()
        try {
            listener.onNotificationPosted(notification())
            advance(1_500)
            assertEquals(RecordingForegroundService.ACTION_START_RECORDING, awaitServiceAction())
            listener.onNotificationRemoved(notification())
            listener.onNotificationPosted(notification(packageName = "com.facebook.orca"))
            advance(3_500)
            assertEquals(RecordingForegroundService.ACTION_STOP_RECORDING, awaitServiceAction())
            assertEquals(RecordingForegroundService.ACTION_START_RECORDING, awaitServiceAction())
        } finally { controller.destroy() }
    }

    @Test
    fun messagesRingingAndNonCommunicationAudio_doNotStartRecording() {
        val controller = Robolectric.buildService(CallNotificationListener::class.java).create()
        audio = controller.get().getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        try {
            val listener = controller.get()
            listener.onNotificationPosted(notification(category = Notification.CATEGORY_MESSAGE))
            listener.onNotificationPosted(notification(type = Notification.CallStyle.CALL_TYPE_INCOMING))
            advance(1_500)
            assertNull(shadowOf(app).nextStartedService)
            audio.mode = AudioManager.MODE_NORMAL
            listener.onNotificationPosted(notification())
            advance(1_000)
            assertNull(shadowOf(app).nextStartedService)
        } finally { controller.destroy() }
    }

    @Test
    fun disabledFallbackAndUnlistedApps_doNotStartRecording() {
        val controller = Robolectric.buildService(CallNotificationListener::class.java).create()
        audio = controller.get().getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        try {
            controller.get().onNotificationPosted(notification(packageName = "unlisted.app"))
            advance(1_500)
            preferences.setNotificationCallFallbackEnabled(false)
            controller.get().onNotificationPosted(notification())
            advance(1_500)
            assertNull(shadowOf(app).nextStartedService)
        } finally { controller.destroy() }
    }

    @Test
    fun completedIncomingNotification_doesNotChangeDirectionOfTheNextCall() {
        preferences.setAutoRecordOutgoingEnabled(false)
        val controller = Robolectric.buildService(CallNotificationListener::class.java).create()
        try {
            val listener = controller.get()
            listener.onNotificationPosted(notification(type = Notification.CallStyle.CALL_TYPE_INCOMING))
            listener.onNotificationPosted(notification(category = Notification.CATEGORY_MISSED_CALL))
            listener.onNotificationPosted(notification())
            advance(1_500)
            assertEquals(RecordingForegroundService.ACTION_STANDBY, awaitServiceAction())
        } finally { controller.destroy() }
    }

    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))

    private fun awaitServiceAction(): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(app).nextStartedService?.let { return it.action }
            Thread.yield()
        } while (System.nanoTime() < deadline)
        fail("No recording service command arrived")
        return null
    }

    private fun notification(
        packageName: String = "com.whatsapp",
        category: String = Notification.CATEGORY_CALL,
        type: Int = Notification.CallStyle.CALL_TYPE_ONGOING
    ): StatusBarNotification {
        val notification = Notification().apply {
            this.category = category
            flags = Notification.FLAG_ONGOING_EVENT
            extras.putInt(Notification.EXTRA_CALL_TYPE, type)
        }
        return StatusBarNotification(packageName, packageName, 1, null, 10001, 0, 0, notification, Process.myUserHandle(), 0)
    }
}
