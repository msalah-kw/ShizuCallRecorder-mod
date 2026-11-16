package com.kitsumed.shizucallrecorder.services.shell

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class DiagnosticSnapshotTest {
    @Test fun missingExecutableProducesExplicitFailureInsteadOfEmptySuccess() {
        val captured = DiagnosticSnapshot.runCommand(listOf("nonexistent-scr-diagnostic-command"))
        assertFalse(captured.succeeded)
        assertTrue(captured.failure!!.contains("command unavailable"))
    }

    @Test fun successfulCommandCapturesMergedOutputAndExitStatus() {
        val captured = DiagnosticSnapshot.runCommand(listOf(javaBinary(), "-version"))
        assertTrue(captured.output.contains("version"))
        assertEquals(0, captured.exitCode)
        assertTrue(captured.succeeded)
    }

    @Test fun largeOutputIsDrainedWithoutKillingTheCommand() {
        // HONOR report: full dumpsys pipes were closed at the size cap and successful samples became errors.
        for (size in listOf(DiagnosticSnapshot.OUTPUT_LIMIT, DiagnosticSnapshot.OUTPUT_LIMIT + 1, DiagnosticSnapshot.OUTPUT_LIMIT * 8)) {
            val captured = probe("large", size.toString())
            assertTrue("Command must complete for size=$size: ${captured.failure}", captured.succeeded)
            assertEquals(minOf(size, DiagnosticSnapshot.OUTPUT_LIMIT), captured.output.length)
            assertEquals(size > DiagnosticSnapshot.OUTPUT_LIMIT, captured.truncated)
        }
    }

    @Test fun eofBeforeProcessExitDoesNotTriggerPrematureTermination() {
        val captured = probe("delayed-exit")
        assertTrue(captured.succeeded)
        assertEquals("before-exit", captured.output)
    }

    @Test fun timedOutCommandKeepsPartialEvidence() {
        val captured = probe("timeout")
        assertFalse(captured.succeeded)
        assertTrue(captured.failure!!.contains("timed out"))
        assertTrue(captured.output.contains("partial-evidence"))
    }

    @Test fun failedCommandKeepsMergedErrorOutputAndExitCode() {
        val captured = probe("failure")
        assertFalse(captured.succeeded)
        assertEquals(7, captured.exitCode)
        assertTrue(captured.output.contains("Permission denied"))
    }

    @Test fun reportSummarySeparatesShortenedOutputFromFailedCommands() {
        val report = DiagnosticSnapshot.formatReport(listOf(
            "audio" to probe("large", (DiagnosticSnapshot.OUTPUT_LIMIT + 1).toString()),
            "media.audio_flinger" to probe("large", "4"),
            "logcat" to probe("failure")
        ), "test-sample")
        val summary = DiagnosticSnapshot.parseSummary(report)
        assertEquals(listOf("audio"), summary.truncated)
        assertEquals(listOf("logcat"), summary.failed)
        assertTrue(report.contains("remaining output drained"))
        assertTrue(report.contains("[exit=7]"))
    }

    @Test fun unrecognizedSummaryCannotReportHealthyCapture() {
        for (header in listOf("Snapshot old report [exit=0]", "SCR_DIAGNOSTICS_V2 failed=- truncated=unknown")) {
            try {
                DiagnosticSnapshot.parseSummary(header)
                fail("Expected invalid summary rejection")
            } catch (_: IOException) { }
        }
    }

    private fun probe(vararg arguments: String): DiagnosticCommandResult {
        val location = requireNotNull(DiagnosticProbe::class.java.protectionDomain?.codeSource?.location)
        val classes = File(location.toURI()).absolutePath
        return DiagnosticSnapshot.runCommand(listOf(javaBinary(), "-cp", classes, DiagnosticProbe::class.java.name) + arguments)
    }

    private fun javaBinary(): String = File(System.getProperty("java.home"),
        "bin/java" + if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else "").absolutePath
}
