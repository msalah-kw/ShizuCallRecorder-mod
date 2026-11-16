package com.kitsumed.shizucallrecorder.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class DiagnosticFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rotationRetainsNewestEvidenceWithinDiskLimit() {
        val folder = temporary.newFolder()
        val store = DiagnosticFiles(folder)
        repeat(75) { store.append("app.log", "entry=$it\n" + "x".repeat(120_000)) }
        assertTrue(folder.listFiles()!!.sumOf { it.length() } <= 3L * DiagnosticFiles.MAX_BYTES)
        assertEquals(3, folder.listFiles()!!.size)
        val exported = ByteArrayOutputStream().also(store::writeTo).toString("UTF-8")
        assertFalse(exported.contains("entry=0\n"))
        assertTrue(exported.contains("entry=74\n"))
    }

    @Test fun writeFailureDoesNotPreventLaterRecovery() {
        val folder = File(temporary.root, "diagnostics")
        folder.writeText("Storage path temporarily blocked")
        val store = DiagnosticFiles(folder)
        try { store.append("app.log", "lost"); fail("Expected storage failure") } catch (_: IOException) { }
        assertTrue(folder.delete())
        store.append("app.log", "recovered")
        assertEquals("recovered", File(folder, "app.log").readText())
    }

    @Test fun reopeningAndExportingKeepsBothAppAndShellEvidence() {
        val folder = temporary.newFolder()
        DiagnosticFiles(folder).append("app.log", "call detected\n")
        DiagnosticFiles(folder).append("shizuku.log", "capture permission denied\n")
        val store = DiagnosticFiles(folder)
        val report = ByteArrayOutputStream().also(store::writeTo).toString("UTF-8")
        assertTrue(report.contains("call detected"))
        assertTrue(report.contains("capture permission denied"))
        assertEquals(2, folder.listFiles()!!.size)
    }
}
