package com.kitsumed.shizucallrecorder.services.shell.recording

import android.app.Application
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.MediaCrypto
import android.media.MediaMuxer
import android.view.Surface
import android.os.ParcelFileDescriptor
import com.kitsumed.shizucallrecorder.data.recording.RecordingCodec
import com.kitsumed.shizucallrecorder.data.recording.CapturePlan
import com.kitsumed.shizucallrecorder.data.recording.RecordingFailure
import com.kitsumed.shizucallrecorder.data.recording.RecordingPlan
import com.kitsumed.shizucallrecorder.IRecordingCallback
import com.kitsumed.shizucallrecorder.services.shell.recording.encoding.AudioEncoder
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [AudioEncoderTest.StalledCodec::class])
class AudioEncoderTest {
    @Before
    fun resetCodec() {
        StalledCodec.inputUnavailable = false
        StalledCodec.failConfiguration = false
        StalledCodec.releaseCount = 0
    }

    @Test
    fun failedCodecConfiguration_releasesAllocatedCodec() {
        StalledCodec.failConfiguration = true
        val file = File.createTempFile("failed-configuration", ".ogg")
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                assertThrows(IllegalStateException::class.java) {
                    AudioEncoder(descriptor.fileDescriptor, RecordingCodec.OPUS, 1, 16_000)
                }
                assertEquals(1, StalledCodec.releaseCount)
            }
        } finally { file.delete() }
    }

    @Test
    @Config(shadows = [StalledCodec::class, RejectedMuxer::class])
    fun failedMuxerAllocation_releasesPreviouslyAllocatedCodec() {
        val file = File.createTempFile("failed-muxer", ".ogg")
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                assertThrows(IOException::class.java) {
                    AudioEncoder(descriptor.fileDescriptor, RecordingCodec.OPUS, 1, 16_000)
                }
                assertEquals(1, StalledCodec.releaseCount)
            }
        } finally { file.delete() }
    }

    @Test
    fun rejectedInitialization_returnsFailureAndClosesTheRemoteOutput() {
        val file = File.createTempFile("rejected-recording", ".ogg")
        val failures = mutableListOf<RecordingFailure>()
        val callback = object : IRecordingCallback.Stub() {
            override fun onRecordingFailure(error: RecordingFailure) { failures.add(error) }
        }
        val plan = RecordingPlan(CapturePlan.AudioRecord(MediaRecorder.AudioSource.VOICE_CALL), null, true, RecordingCodec.OPUS, 16_000)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                assertFalse(ShellRecordingEngine().start(descriptor, plan, callback))
                assertFalse(descriptor.fileDescriptor.valid())
                assertTrue(failures.isEmpty())
            }
        } finally { file.delete() }
    }
    @Test(timeout = 10_000)
    fun encoderWithoutInputBuffers_timesOutInsteadOfWaitingForever() {
        StalledCodec.inputUnavailable = true
        withEncoder { encoder ->
            assertThrows(TimeoutException::class.java) { encoder.encode(ByteBuffer.allocateDirect(640), 0) }
        }
    }

    @Test(timeout = 10_000)
    fun encoderWithoutEndOfStream_timesOutDuringFinalization() {
        StalledCodec.inputUnavailable = false
        withEncoder { encoder ->
            assertThrows(TimeoutException::class.java) { encoder.finish() }
        }
    }

    private fun withEncoder(check: (AudioEncoder) -> Unit) {
        val file = File.createTempFile("encoder-deadline", ".ogg")
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                val encoder = AudioEncoder(descriptor.fileDescriptor, RecordingCodec.OPUS, 1, 16_000)
                try { check(encoder) } finally { encoder.release() }
            }
        } finally { file.delete() }
    }

    // A stalled platform codec is the boundary condition under test, not a replacement for our encoder.
    @Implements(MediaCodec::class)
    class StalledCodec : ShadowMediaCodec() {
        companion object {
            var inputUnavailable = false
            var failConfiguration = false
            var releaseCount = 0
        }
        @Implementation
        override fun native_configure(keys: Array<String>, values: Array<Any>, surface: Surface?, crypto: MediaCrypto?, descrambler: Any?, flags: Int) {
            if (failConfiguration) throw IllegalStateException("Codec configuration rejected")
            super.native_configure(keys, values, surface, crypto, descrambler, flags)
        }
        @Implementation
        protected fun native_release() { releaseCount++ }
        @Implementation
        override fun native_dequeueInputBuffer(timeoutUs: Long): Int =
            if (inputUnavailable) MediaCodec.INFO_TRY_AGAIN_LATER else super.native_dequeueInputBuffer(timeoutUs)
        @Implementation
        override fun native_dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int = MediaCodec.INFO_TRY_AGAIN_LATER
    }

    @Implements(MediaMuxer::class)
    class RejectedMuxer {
        companion object {
            @JvmStatic
            @Implementation
            fun nativeSetup(descriptor: FileDescriptor, format: Int): Long = throw IOException("Muxer allocation rejected")
        }
    }
}
