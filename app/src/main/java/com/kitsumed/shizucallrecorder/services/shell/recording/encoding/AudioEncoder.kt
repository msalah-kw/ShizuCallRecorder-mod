/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.shell.recording.encoding

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.kitsumed.shizucallrecorder.data.recording.RecordingCodec
import com.kitsumed.shizucallrecorder.services.shell.recording.AudioConfig
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * Encodes PCM audio with Android MediaCodec and writes the encoded samples directly into the final container using MediaMuxer.
 */
class AudioEncoder(
    outputFileDescriptor: FileDescriptor,
    private val codec: RecordingCodec,
    private val channelCount: Int,
    private val bitrate: Int
) {

    companion object {
        /**
         * Timeout used while waiting for MediaCodec buffers.
         * NOTE: A too long timeout will cause frames drop.
         *
         * Unit: microseconds
         */
        private const val CODEC_TIMEOUT_US = 1_000L
        private const val CODEC_STALL_TIMEOUT_NS = 2_000_000_000L
    }

    private val mediaCodec = MediaCodec.createEncoderByType(codec.mimeType)

    private val mediaMuxer = MediaMuxer(outputFileDescriptor, codec.outputFormat)

    private val bufferInfo = MediaCodec.BufferInfo()

    private var codecStarted = false
    private var muxerStarted = false
    private var audioTrack = -1
    private var inputEnded = false
    private var released = false
    private var releaseSucceeded = true

    init {
        if (channelCount == 2 && codec.minStereoBitRate != null && bitrate < codec.minStereoBitRate) {
            // In my tests is the minimum, internally the encoder behave weirdly, make two channel of "mono" audio, silence one, saturate, etc.
            // The mediaCodec and mediaMuxer will not throw any error, and will tell you everything is fine with two channels, but it will NOT.
            throw IllegalArgumentException("Bitrate is too low to encode stereo audio with codec '${codec.name}'. It must be at least ${codec.minStereoBitRate} raw kbp.")
        }
        if (channelCount == 1 && codec.isStereoOnly) {
            throw IllegalArgumentException("Codec '${codec.name}' only supports stereo audio. Mono audio is not supported.")
        }

        val format = MediaFormat.createAudioFormat(
                codec.mimeType,
                AudioConfig.SAMPLE_RATE,
                channelCount
            )

        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)

        // Calculate our PCM block size and add a small margin of x2 since I have read being too strict may cause a crash? Did not happen for me.
        val maxInputBlockSize = (if (channelCount == 2) AudioConfig.STEREO_BLOCK_SIZE_BYTES else AudioConfig.BLOCK_SIZE_BYTES) * 2
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputBlockSize)

        // Apply codec-specific settings (e.g., AAC profile) to the MediaFormat
        codec.applyCodecFormat(format);

        // Attempt to enable VBR encoding mode if supported by the codec
        runCatching {
            val codecCaps = mediaCodec.codecInfo.getCapabilitiesForType(codec.mimeType)
            val encoderCaps = codecCaps.encoderCapabilities
            if (encoderCaps != null) {
                if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    AppLogger.d("VBR encoding mode is supported and has been enabled for ${codec.key}")
                }
            }
        }.onFailure {
            AppLogger.w("Failed to query codec capabilities for VBR support. Skipping.", it)
        }

        mediaCodec.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        mediaCodec.start()
        codecStarted = true
    }

    /**
     * Encodes one PCM block.
     *
     * @param pcm PCM16 data.
     * @param presentationTimeUs Presentation timestamp of the first frame.
     * @param deadlineNanos Monotonic deadline imposed by session shutdown, if any.
     * @throws TimeoutException if the codec cannot complete before the deadline.
     */
    fun encode(pcm: ByteBuffer, presentationTimeUs: Long, deadlineNanos: Long = Long.MAX_VALUE) {
        check(!released) { "AudioEncoder has already been released." }
        val inputDeadline = minOf(deadlineNanos, System.nanoTime() + CODEC_STALL_TIMEOUT_NS)

        val frameSize = channelCount * AudioConfig.PCM_BYTES_PER_SAMPLE
        var sourceOffset = 0

        while (pcm.hasRemaining()) {
            // Drain output FIRST to prevent deadlocks
            drainOutput(deadlineNanos = inputDeadline)

            val inputIndex = waitForInputBuffer(inputDeadline)
            if (inputIndex < 0) {
                // The encoder was released while we were waiting. Exit the loop safely.
                break
            }

            val inputBuffer = mediaCodec.getInputBuffer(inputIndex) ?: throw IllegalStateException("MediaCodec returned a null input buffer.")
            // Android docs claim the input buffer is cleared, but just to be safe.
            inputBuffer.clear()

            val bytesToCopy = minOf(pcm.remaining(), inputBuffer.remaining())

            // Fast-path buffer copying
            if (bytesToCopy == pcm.remaining()) {
                inputBuffer.put(pcm)
            } else {
                // We need to limit the PCM buffer to the number of bytes we want to copy.
                val oldLimit = pcm.limit()
                pcm.limit(pcm.position() + bytesToCopy)
                inputBuffer.put(pcm)
                // Restore the original limit of the PCM buffer so that it can be used for subsequent reads.
                pcm.limit(oldLimit)
            }

            val chunkFrameOffset = sourceOffset / frameSize
            val chunkPresentationTimeUs = presentationTimeUs + (chunkFrameOffset * 1_000_000L) / AudioConfig.SAMPLE_RATE

            mediaCodec.queueInputBuffer(
                inputIndex,
                0, // Offset is 0 because we cleared the buffer and wrote from the beginning
                bytesToCopy,
                chunkPresentationTimeUs,
                0
            )

            sourceOffset += bytesToCopy
        }
        drainOutput(deadlineNanos = inputDeadline)
    }

    /**
     * Finishes the encoder and drains all remaining encoded samples.
     * @throws TimeoutException if the codec does not reach end-of-stream within two seconds.
     */
    fun finish() {
        if (released || !codecStarted || inputEnded) {
            return
        }

        val deadlineNanos = System.nanoTime() + CODEC_STALL_TIMEOUT_NS
        val inputIndex = waitForInputBuffer(deadlineNanos)
        if (inputIndex < 0) return

        mediaCodec.queueInputBuffer(
            inputIndex,
            0,
            0,
            0,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )

        inputEnded = true

        drainOutput(waitForEndOfStream = true, deadlineNanos = deadlineNanos)
    }

    /**
     * Releases MediaCodec and MediaMuxer.
     * @return false if either resource could not be stopped or released.
     */
    fun release(): Boolean {
        if (released) {
            return releaseSucceeded
        }

        released = true

        val codecStopped = runCatching {
            if (codecStarted) {
                mediaCodec.stop()
            }
        }.onFailure {
            AppLogger.w("Failed to stop MediaCodec.", it)
        }.isSuccess

        val codecReleased = runCatching {
            mediaCodec.release()
        }.onFailure {
            AppLogger.w("Failed to release MediaCodec.", it)
        }.isSuccess

        codecStarted = false

        val muxerStopped = runCatching {
            if (muxerStarted) {
                mediaMuxer.stop()
            }
        }.onFailure {
            AppLogger.w("Failed to stop MediaMuxer.", it)
        }.isSuccess

        val muxerReleased = runCatching {
            mediaMuxer.release()
        }.onFailure {
            AppLogger.w("Failed to release MediaMuxer.", it)
        }.isSuccess

        muxerStarted = false
        audioTrack = -1
        releaseSucceeded = codecStopped && codecReleased && muxerStopped && muxerReleased
        return releaseSucceeded
    }

    /**
     * Waits for an available input buffer from MediaCodec.
     *
     * @return The index of the available input buffer, or -1 if the encoder was released.
     */
    private fun waitForInputBuffer(deadlineNanos: Long): Int {
        while (!released) {
            checkDeadline(deadlineNanos)
            val inputIndex = mediaCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)

            if (inputIndex >= 0) {
                return inputIndex
            }
            drainOutput(deadlineNanos = deadlineNanos)
        }
        return -1
    }

    /**
     * Drains the output buffers from MediaCodec (encoded data) and writes them to MediaMuxer (write the encoded data to the file).
     * @param waitForEndOfStream If true, will continue draining until the end of stream is reached.
     */
    private fun drainOutput(waitForEndOfStream: Boolean = false, deadlineNanos: Long) {
        while (!released) {
            checkDeadline(deadlineNanos)
            when (val outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                // When no more encoded data is waiting, we can exit
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (waitForEndOfStream) {
                        continue
                    }
                    return
                }
                // MediaCodec tell us that the output format will follow X format, pass it to MediaMuxer
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        throw IllegalStateException("MediaCodec output format changed after MediaMuxer started.")
                    }
                    audioTrack = mediaMuxer.addTrack(mediaCodec.outputFormat)
                    mediaMuxer.start()
                    muxerStarted = true
                    AppLogger.i("MediaMuxer started. codec=${codec.key}, track=$audioTrack")
                }
                else -> {
                    // If outputIndex is negative, it means the encoder has not output to give us. We can loop again.
                    // (This should never be true because we already handled the INFO_TRY_AGAIN_LATER case above, but just to be safe)
                    if (outputIndex < 0) {
                        continue
                    }

                    try {
                        val outputBuffer = mediaCodec.getOutputBuffer(outputIndex)

                        // Only write the sample if it's not a codec config buffer and has data.
                        if (outputBuffer != null && bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            writeSample(outputBuffer, bufferInfo)
                        }
                    } finally {
                        // Release the output buffer back to MediaCodec so they can use it again.
                        mediaCodec.releaseOutputBuffer(outputIndex, false)
                    }

                    // If we've reached the end of the stream, we can exit the loop.
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun checkDeadline(deadlineNanos: Long) {
        if (System.nanoTime() - deadlineNanos >= 0) {
            throw TimeoutException("Audio codec did not complete within its two-second deadline.")
        }
    }

    /**
     * Writes an encoded sample to the MediaMuxer.
     *
     * @param buffer The buffer containing the encoded sample data.
     * @param bufferInfo The buffer info associated with the encoded sample.
     * @throws IllegalStateException If the MediaMuxer has not been started or if the audio track index is invalid.
     */
    private fun writeSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (!muxerStarted || audioTrack < 0) {
            throw IllegalStateException("Attempted to write encoded audio before MediaMuxer started.")
        }

        mediaMuxer.writeSampleData(
            audioTrack,
            buffer,
            bufferInfo
        )
    }
}
