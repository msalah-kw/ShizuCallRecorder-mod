package com.kitsumed.shizucallrecorder.services.shell

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DiagnosticSnapshotTest {
    @Test fun missingExecutableProducesExplicitFailureInsteadOfEmptySuccess() {
        val captured = DiagnosticSnapshot.runCommand(listOf("nonexistent-scr-diagnostic-command"))
        assertTrue(captured.contains("command unavailable"))
    }

    @Test fun successfulCommandCapturesMergedOutputAndExitStatus() {
        val binary = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else "")
        val captured = DiagnosticSnapshot.runCommand(listOf(binary.absolutePath, "-version"))
        assertTrue(captured.contains("version"))
        assertTrue(captured.contains("[exit=0]"))
    }
}
