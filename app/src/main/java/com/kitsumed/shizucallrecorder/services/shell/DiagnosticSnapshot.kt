package com.kitsumed.shizucallrecorder.services.shell

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.io.IOException

/** Fixed read-only commands only. Never executes caller-provided shell text. */
object DiagnosticSnapshot {
    fun collect(): String = buildString {
        appendLine("Snapshot wallTime=${java.time.Instant.now()} elapsed=${java.time.Duration.ofMillis(android.os.SystemClock.elapsedRealtime())} uid=${android.os.Process.myUid()}")
        for (command in listOf(
            listOf("/system/bin/dumpsys", "audio"),
            listOf("/system/bin/dumpsys", "media.audio_flinger"),
            listOf("/system/bin/logcat", "-d", "-t", "300", "-v", "threadtime", "AudioRecord:V", "AudioFlinger:V", "AudioPolicyManager:V", "MediaCodec:V", "ACodec:V", "AndroidRuntime:E", "*:S")
        )) {
            appendLine("=== ${command.joinToString(" ")} ===")
            appendLine(runCommand(command))
        }
    }

    internal fun runCommand(command: List<String>): String {
        val executor = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            child = ProcessBuilder(command).redirectErrorStream(true).start()
            val running = child
            val capture = executor.submit<String> {
                running.inputStream.use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val block = ByteArray(2048)
                    while (buffer.size() < 24 * 1024) {
                        val read = stream.read(block, 0, minOf(block.size, 24 * 1024 - buffer.size()))
                        if (read < 0) break
                        buffer.write(block, 0, read)
                    }
                    val bytes = buffer.toByteArray()
                    String(bytes, Charsets.UTF_8) + if (bytes.size == 24 * 1024) "\n[output limit reached]" else ""
                }
            }
            val text = capture.get(3, TimeUnit.SECONDS)
            if (!running.waitFor(100, TimeUnit.MILLISECONDS)) return "$text\n[command still running; terminated]"
            return "$text\n[exit=${running.exitValue()}]"
        } catch (_: TimeoutException) {
            return "[command timed out after 3 seconds]"
        } catch (failure: IOException) {
            return "[command unavailable: ${failure.message}]"
        } catch (failure: ExecutionException) {
            return "[command read failed: ${failure.cause}]"
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            return "[command interrupted]"
        } finally {
            child?.destroyForcibly()
            executor.shutdownNow()
        }
    }
}
