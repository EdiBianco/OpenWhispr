package com.edib.openwhispr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceWatchdogTest {
    private val ours = "com.edib.openwhispr/com.edib.openwhispr.WhisperAccessibilityService"

    @Test
    fun `finds our service among several enabled services`() {
        assertTrue(ServiceWatchdog.containsComponent("com.other/.Svc:$ours", ours))
    }

    @Test
    fun `is false when the setting is empty or missing`() {
        assertFalse(ServiceWatchdog.containsComponent(null, ours))
        assertFalse(ServiceWatchdog.containsComponent("", ours))
    }

    @Test
    fun `does not match a similar package name`() {
        assertFalse(ServiceWatchdog.containsComponent(
            "com.edib.openwhispr.beta/com.edib.openwhispr.WhisperAccessibilityService", ours))
    }
}
