package com.kitsumed.shizucallrecorder.services

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.SQLException
import android.net.Uri
import android.provider.ContactsContract
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.RawCallData
import com.kitsumed.shizucallrecorder.services.recording.RecordingForegroundService
import com.kitsumed.shizucallrecorder.utils.PhoneNumberManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingDecisionEngineTest {
    private lateinit var application: Application
    private lateinit var preferences: AppPreferences
    private lateinit var contacts: ContactsProvider

    @Before
    fun prepareCallPermissionsAndPreferences() {
        application = RuntimeEnvironment.getApplication()
        // These application-scoped singletons must not retain a previous Robolectric Application.
        ReflectionHelpers.setStaticField(RecordingDecisionEngine::class.java, "INSTANCE", null)
        ReflectionHelpers.setStaticField(PhoneNumberManager::class.java, "INSTANCE", null)
        preferences = AppPreferences(application)
        preferences.setAutoRecordIncomingEnabled(true)
        preferences.setAutoRecordOutgoingEnabled(true)
        preferences.setVibrationEnabled(false)
        contacts = ContactsProvider()
        contacts.attachInfo(application, ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, contacts)
    }

    @Test
    fun missingContactPermission_keepsFilteredCallsInStandby() {
        shadowOf(application).denyPermissions(Manifest.permission.READ_CONTACTS)
        for (direction in CallDirection.entries) {
            for (mode in listOf(AppPreferences.IgnoreContactsMode.ALL, AppPreferences.IgnoreContactsMode.SELECTED)) {
                configureFilter(mode, setOf("excluded"))
                assertEquals("$direction $mode", RecordingForegroundService.ACTION_STANDBY, decision(direction))
            }
        }
    }

    @Test
    fun unavailableContactProvider_keepsFilteredCallsInStandby() {
        shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS)
        for (failure in listOf(null, SQLException("Contacts unavailable"), SecurityException("Permission revoked"))) {
            contacts.failure = failure
            contacts.lookupKeys = null
            for (mode in listOf(AppPreferences.IgnoreContactsMode.ALL, AppPreferences.IgnoreContactsMode.SELECTED)) {
                configureFilter(mode, setOf("excluded"))
                assertEquals("$mode $failure", RecordingForegroundService.ACTION_STANDBY, decision(CallDirection.INCOMING))
            }
        }
    }

    @Test
    fun resolvedAndDisabledFilters_preserveTheRequestedRecordingPolicy() {
        shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS)
        val cases = listOf(
            Triple(AppPreferences.IgnoreContactsMode.ALL, emptyList(), true),
            Triple(AppPreferences.IgnoreContactsMode.ALL, listOf("other"), false),
            Triple(AppPreferences.IgnoreContactsMode.SELECTED, listOf("excluded"), false),
            Triple(AppPreferences.IgnoreContactsMode.SELECTED, listOf("other"), true),
            Triple(AppPreferences.IgnoreContactsMode.NONE, null, true),
        )
        for ((mode, keys, shouldRecord) in cases) {
            configureFilter(mode, setOf("excluded"))
            contacts.lookupKeys = keys
            val expected = if (shouldRecord) RecordingForegroundService.ACTION_START_RECORDING else RecordingForegroundService.ACTION_STANDBY
            assertEquals("$mode $keys", expected, decision(CallDirection.OUTGOING))
        }
        configureFilter(AppPreferences.IgnoreContactsMode.SELECTED, emptySet())
        contacts.lookupKeys = null
        assertEquals(RecordingForegroundService.ACTION_START_RECORDING, decision(CallDirection.INCOMING))
    }

    private fun configureFilter(mode: AppPreferences.IgnoreContactsMode, excluded: Set<String>) {
        preferences.setIgnoreContactsModeIncoming(mode)
        preferences.setIgnoreContactsModeOutgoing(mode)
        preferences.setIgnoredContactsIncoming(excluded)
        preferences.setIgnoredContactsOutgoing(excluded)
    }

    @Test
    fun unidentifiedVoip_keepsContactExclusionsAndUnknownDirectionPolicy() {
        preferences.setIgnoreAnonymousIncomingEnabled(false)
        configureFilter(AppPreferences.IgnoreContactsMode.NONE, emptySet())
        fun voipDecision(): String? {
            runBlocking {
                RecordingDecisionEngine.getInstance(application).executeDecisionPipeline(
                    RawCallData("", CallDirection.UNKNOWN, packageName = "com.whatsapp", isCallAudioVoIpBased = true)
                )
            }
            return shadowOf(application).nextStartedService.action
        }
        assertEquals(RecordingForegroundService.ACTION_START_RECORDING, voipDecision())
        preferences.setAutoRecordIncomingEnabled(false)
        assertEquals(RecordingForegroundService.ACTION_STANDBY, voipDecision())
        preferences.setAutoRecordIncomingEnabled(true)
        preferences.setAutoRecordOutgoingEnabled(false)
        assertEquals(RecordingForegroundService.ACTION_STANDBY, voipDecision())
        preferences.setAutoRecordOutgoingEnabled(true)
        configureFilter(AppPreferences.IgnoreContactsMode.SELECTED, setOf("excluded"))
        assertEquals(RecordingForegroundService.ACTION_STANDBY, voipDecision())
        configureFilter(AppPreferences.IgnoreContactsMode.SELECTED, emptySet())
        assertEquals(RecordingForegroundService.ACTION_START_RECORDING, voipDecision())
    }

    private fun decision(direction: CallDirection): String? {
        runBlocking {
            RecordingDecisionEngine.getInstance(application).executeDecisionPipeline(
                RawCallData("+12025550173", direction)
            )
        }
        return shadowOf(application).nextStartedService.action
    }

    // The Contacts provider is an external data boundary, including permission and availability failures.
    class ContactsProvider : ContentProvider() {
        var failure: RuntimeException? = null
        var lookupKeys: List<String>? = emptyList()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            failure?.let { throw it }
            val keys = lookupKeys ?: return null
            val columns = projection ?: arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY)
            return MatrixCursor(columns).apply {
                keys.forEach { key -> addRow(columns.map { if (it == ContactsContract.PhoneLookup.LOOKUP_KEY) key else "1" }) }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    }
}
