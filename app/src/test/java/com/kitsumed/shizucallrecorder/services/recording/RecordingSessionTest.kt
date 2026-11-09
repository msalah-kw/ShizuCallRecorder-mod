package com.kitsumed.shizucallrecorder.services.recording

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaRecorder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.provider.DocumentsContract
import com.kitsumed.shizucallrecorder.ILogCallback
import com.kitsumed.shizucallrecorder.IRecordingCallback
import com.kitsumed.shizucallrecorder.IShellService
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import com.kitsumed.shizucallrecorder.data.recording.CapturePlan
import com.kitsumed.shizucallrecorder.data.recording.RecordingCodec
import com.kitsumed.shizucallrecorder.data.recording.RecordingFailure
import com.kitsumed.shizucallrecorder.data.recording.RecordingPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import com.kitsumed.shizucallrecorder.system.storage.SafHelper
import org.robolectric.util.ReflectionHelpers
import org.robolectric.android.controller.ServiceController
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingSessionTest {
    private lateinit var service: TestService
    private lateinit var documents: RecordingDocumentsProvider
    private val plan = RecordingPlan(CapturePlan.AudioRecord(MediaRecorder.AudioSource.VOICE_CALL), null, false, RecordingCodec.OPUS, 16_000)
    private val metadata = EnrichedCallData("+12025550173", direction = CallDirection.OUTGOING)

    @Before
    fun prepareWritableRecordingFolder() {
        service = Robolectric.buildService(TestService::class.java).create().get()
        documents = RecordingDocumentsProvider()
        documents.attachInfo(service, ProviderInfo().apply {
            authority = "recording.tests"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal("recording.tests", documents)
        AppPreferences(service).setRecordingFolderUri(DocumentsContract.buildTreeDocumentUri("recording.tests", "root"))
        check(SafHelper.isFolderValid(service, AppPreferences(service).getRecordingFolderUri())) {
            "Test SAF folder must be writable before testing the recording lifecycle"
        }
    }

    @Test
    fun rejectedCapture_throwsInitializationFailureAndDeletesTheEmptyFile() {
        val remote = RemoteShell(acceptStart = false)
        assertThrows(RecordingSession.InitializationException::class.java) {
            RecordingSession(service, remote, metadata, plan, listener { error("No runtime callback expected") })
        }
        assertEquals(1, documents.createdFiles.size)
        assertFalse(documents.createdFiles.single().exists())
    }

    @Test
    fun runtimeFailure_notifiesTheOwnerWithoutReenteringRemoteCleanup() {
        val remote = RemoteShell(acceptStart = true)
        val failures = mutableListOf<RecordingFailure>()
        val session = RecordingSession(service, remote, metadata, plan, listener { failures.add(it) })
        remote.callback!!.onRecordingFailure(RecordingFailure.CAPTURE_READ_FAILED)
        assertEquals(listOf(RecordingFailure.CAPTURE_READ_FAILED), failures)
        assertEquals(0, remote.stopCount)
        session.release()
        session.release()
        assertEquals(1, remote.stopCount)
    }

    private fun listener(onFailure: (RecordingFailure) -> Unit) = object : RecordingSession.SessionListener {
        override fun onSessionFailed(failure: RecordingFailure) { onFailure(failure) }
    }

    @Test
    fun failedRemoteFinalization_remainsAFailureOnRepeatedRelease() {
        val session = RecordingSession(service, RemoteShell(true, false), metadata, plan, listener {})
        assertFalse(session.release())
        assertFalse(session.release())
    }

    @Test
    fun stopCommand_returnsWhileRemoteFinalizationRunsOffTheMainThread() {
        val remote = RemoteShell(acceptStart = true)
        val finishGate = CountDownLatch(1)
        remote.stopGate = finishGate
        val session = RecordingSession(service, remote, metadata, plan, listener {})
        val controller = foregroundSession(session)
        val foregroundService = controller.get()
        try {
            foregroundService.onStartCommand(Intent(foregroundService, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_STOP_RECORDING
            }, 0, 1)
            assertTrue(foregroundService.serviceState.value is RecordingServiceState.Stopping)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(remote.stopEntered.await(3, TimeUnit.SECONDS))
            assertEquals(false, remote.stoppedOnMainThread)
        } finally {
            finishGate.countDown()
            session.release()
            controller.destroy()
        }
    }

    @Test
    fun callArrivingDuringFinalization_isKeptUnlessItHasAlreadyEnded() {
        for (callEndsBeforeSaving in listOf(false, true)) {
            val remote = RemoteShell(true)
            val finishGate = CountDownLatch(1)
            remote.stopGate = finishGate
            val session = RecordingSession(service, remote, metadata, plan, listener {})
            val controller = foregroundSession(session)
            val foreground = controller.get()
            try {
                foreground.onStartCommand(Intent().setAction(RecordingForegroundService.ACTION_STOP_RECORDING), 0, 1)
                foreground.onStartCommand(Intent().apply {
                    action = RecordingForegroundService.ACTION_STANDBY
                    putExtra(EnrichedCallData.EXTRA_METADATA, metadata)
                }, 0, 2)
                if (callEndsBeforeSaving) {
                    foreground.onStartCommand(Intent().setAction(RecordingForegroundService.ACTION_STOP_RECORDING), 0, 3)
                }
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(remote.stopEntered.await(3, TimeUnit.SECONDS))
                finishGate.countDown()
                awaitStandby(foreground)
                assertEquals(if (callEndsBeforeSaving) null else metadata, foreground.serviceState.value.metadata)
            } finally {
                finishGate.countDown()
                session.release()
                controller.destroy()
            }
        }
    }

    private fun foregroundSession(session: RecordingSession): ServiceController<RecordingForegroundService> {
        val controller = Robolectric.buildService(RecordingForegroundService::class.java).create()
        // Seed an established session; Shizuku binding is outside this lifecycle regression.
        val state = ReflectionHelpers.getField<MutableStateFlow<RecordingServiceState>>(controller.get(), "_serviceState")
        state.value = RecordingServiceState.Active(session, false, metadata)
        return controller
    }

    private fun awaitStandby(foreground: RecordingForegroundService) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (foreground.serviceState.value is RecordingServiceState.Standby) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        fail("Recording service did not finish saving")
    }

    class TestService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
        override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int): Int =
            if (uri.authority == "recording.tests") PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfUriPermission(uri, modeFlags)
    }

    class RemoteShell(private val acceptStart: Boolean, private val finalizationResult: Boolean = true) : IShellService.Stub() {
        var callback: IRecordingCallback? = null
        var stopCount = 0
        var stopGate: CountDownLatch? = null
        val stopEntered = CountDownLatch(1)
        @Volatile var stoppedOnMainThread: Boolean? = null
        override fun startRecording(outputFile: ParcelFileDescriptor, plan: RecordingPlan, callback: IRecordingCallback): Boolean {
            this.callback = callback
            return acceptStart
        }
        override fun stopRecording(): Boolean {
            stopCount++
            stoppedOnMainThread = Looper.myLooper() == Looper.getMainLooper()
            stopEntered.countDown()
            check(stopGate?.await(3, TimeUnit.SECONDS) != false) { "Test finalization gate was not released" }
            return finalizationResult
        }
        override fun setLogCallback(listener: ILogCallback, isRedactionEnabled: Boolean) = Unit
        override fun pauseRecording() = Unit
        override fun resumeRecording() = Unit
        override fun grantAppOpByPackage(packageName: String, opName: String, userProfileId: Int): Boolean = throw UnsupportedOperationException()
        override fun grantAppOpByUid(uid: Int, opName: String, userProfileId: Int): Boolean = throw UnsupportedOperationException()
        override fun grantRole(packageName: String, roleName: String, userProfileId: Int): Boolean = throw UnsupportedOperationException()
        override fun destroy() = Unit
    }

    class RecordingDocumentsProvider : ContentProvider() {
        val createdFiles = mutableListOf<File>()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val documentId = DocumentsContract.getDocumentId(uri)
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> documentId
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> if (documentId == "root") DocumentsContract.Document.MIME_TYPE_DIR else "audio/ogg"
                        DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                        DocumentsContract.Document.COLUMN_SIZE -> 0
                        else -> null
                    }
                })
            }
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            val uri = requireNotNull(extras?.getParcelable("uri", Uri::class.java))
            return when (method) {
                "android:createDocument" -> {
                    val file = File(context!!.cacheDir, "recording-${createdFiles.size}.ogg")
                    file.createNewFile()
                    createdFiles.add(file)
                    Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, file.name)) }
                }
                "android:deleteDocument" -> {
                    createdFiles.single { it.name == DocumentsContract.getDocumentId(uri) }.delete()
                    Bundle()
                }
                else -> throw UnsupportedOperationException(method)
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(createdFiles.single { it.name == DocumentsContract.getDocumentId(uri) }, ParcelFileDescriptor.MODE_READ_WRITE)
        override fun getType(uri: Uri) = "audio/ogg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    }
}
