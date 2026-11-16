package com.kitsumed.shizucallrecorder.services.shell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class DiagnosticCommandResult(
    val output: String,
    val exitCode: Int?,
    val truncated: Boolean,
    val failure: String? = null
) {
    val succeeded: Boolean get() = exitCode == 0 && failure == null
}

data class DiagnosticSummary(val failed: List<String>, val truncated: List<String>)

/** Fixed read-only commands only. Never executes caller-provided shell text. */
object DiagnosticSnapshot {
    internal const val OUTPUT_LIMIT = 32 * 1024
    private const val TIMEOUT_SECONDS = 5L
    private val commands = linkedMapOf(
        "audio" to listOf("/system/bin/dumpsys", "audio"),
        "media.audio_flinger" to listOf("/system/bin/dumpsys", "media.audio_flinger"),
        "logcat" to listOf("/system/bin/logcat", "-d", "-t", "300", "-v", "threadtime", "AudioRecord:V", "AudioFlinger:V", "AudioPolicyManager:V", "MediaCodec:V", "ACodec:V", "AndroidRuntime:E", "*:S")
    )

    fun collect(): String {
        val captures = commands.map { (name, command) -> name to runCommand(command) }
        return formatReport(captures, "wallTime=${java.time.Instant.now()} elapsed=${java.time.Duration.ofMillis(android.os.SystemClock.elapsedRealtime())} uid=${android.os.Process.myUid()}")
    }

    internal fun formatReport(captures: List<Pair<String, DiagnosticCommandResult>>, metadata: String): String = buildString {
        val failed = captures.filter { !it.second.succeeded }.map { it.first }
        val shortened = captures.filter { it.second.truncated }.map { it.first }
        appendLine("SCR_DIAGNOSTICS_V2 failed=${failed.joinToString(",").ifEmpty { "-" }} truncated=${shortened.joinToString(",").ifEmpty { "-" }}")
        appendLine("Snapshot $metadata")
        captures.forEach { (name, capture) ->
            appendLine("=== $name ===")
            appendLine(capture.output)
            if (capture.truncated) appendLine("[output limit reached; remaining output drained]")
            capture.failure?.let { appendLine("[command failed: $it]") }
            capture.exitCode?.let { appendLine("[exit=$it]") }
        }
    }

    fun parseSummary(snapshot: String): DiagnosticSummary {
        val header = Regex("^SCR_DIAGNOSTICS_V2 failed=([a-z_.,-]+) truncated=([a-z_.,-]+)$")
            .matchEntire(snapshot.lineSequence().firstOrNull().orEmpty())
            ?: throw IOException("Invalid Shizuku diagnostic summary")
        fun names(group: String): List<String> {
            if (group == "-") return emptyList()
            val parsed = group.split(",")
            if (parsed.distinct().size != parsed.size || parsed.any { it !in commands }) {
                throw IOException("Unknown command in Shizuku diagnostic summary")
            }
            return parsed
        }
        return DiagnosticSummary(names(header.groupValues[1]), names(header.groupValues[2]))
    }

    internal fun runCommand(command: List<String>): DiagnosticCommandResult {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "SCR-DiagnosticOutput").apply { isDaemon = true }
        }
        val output = BoundedOutput()
        var child: Process? = null
        try {
            child = ProcessBuilder(command).redirectErrorStream(true).start()
            val running = child
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            val capture = executor.submit { drain(running, output) }
            // Keep draining even after the retention limit, so dumpsys never blocks on a full pipe.
            if (!running.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return output.result(null, "command timed out after $TIMEOUT_SECONDS seconds")
            }
            val exitCode = running.exitValue()
            capture.get(maxOf(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            return output.result(exitCode, null)
        } catch (_: TimeoutException) {
            return output.result(null, "output drain timed out after $TIMEOUT_SECONDS seconds")
        } catch (failure: IOException) {
            return output.result(null, "command unavailable: ${failure.message}")
        } catch (failure: SecurityException) {
            return output.result(null, "command denied: ${failure.message}")
        } catch (failure: ExecutionException) {
            return output.result(null, "command read failed: ${failure.cause}")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return output.result(null, "command interrupted")
        } finally {
            child?.destroyForcibly()
            executor.shutdownNow()
        }
    }

    private fun drain(child: Process, output: BoundedOutput) {
        child.inputStream.use { stream ->
            val block = ByteArray(4096)
            while (true) {
                val read = stream.read(block)
                if (read < 0) break
                output.append(block, read)
            }
        }
    }

    private class BoundedOutput {
        private val bytes = ByteArrayOutputStream()
        private var shortened = false

        @Synchronized fun append(block: ByteArray, count: Int) {
            val retained = minOf(count, OUTPUT_LIMIT - bytes.size())
            bytes.write(block, 0, retained)
            if (retained < count) shortened = true
        }

        @Synchronized fun result(exitCode: Int?, failure: String?): DiagnosticCommandResult =
            DiagnosticCommandResult(bytes.toString("UTF-8"), exitCode, shortened, failure)
    }
}
