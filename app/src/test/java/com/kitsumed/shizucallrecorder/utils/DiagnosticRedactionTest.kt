package com.kitsumed.shizucallrecorder.utils

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactionTest {
    @Test fun diagnosticTimestampSurvivesWhilePhoneNumberIsRemoved() {
        val safe = AppLogger.redact("wallTime=2026-11-16T12:45:00Z elapsed=PT4H2M5.123S caller=+12025550101")
        assertTrue(safe.contains("2026-11-16T12:45:00Z"))
        assertTrue(safe.contains("PT4H2M5.123S"))
        assertFalse(safe.contains("12025550101"))
        assertTrue(safe.contains("[PHONE_REDACTED]"))
    }
}
