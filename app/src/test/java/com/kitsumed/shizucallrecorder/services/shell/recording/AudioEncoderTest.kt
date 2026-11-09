package com.kitsumed.shizucallrecorder.services.shell.recording

import android.app.Application
import android.media.MediaCodec
import android.media.MediaRecorder
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [AudioEncoderTest.StalledCodec::class])
class AudioEncoderTest {
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
        companion object { var inputUnavailable = false }
        @Implementation
        override fun native_dequeueInputBuffer(timeoutUs: Long): Int =
            if (inputUnavailable) MediaCodec.INFO_TRY_AGAIN_LATER else super.native_dequeueInputBuffer(timeoutUs)
        @Implementation
        override fun native_dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int = MediaCodec.INFO_TRY_AGAIN_LATER
    }
}
