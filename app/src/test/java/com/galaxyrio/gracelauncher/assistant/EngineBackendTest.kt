package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineBackendTest {
    @Test fun knownEmulatorsSkipGpu() {
        assertFalse(AssistantController.hasHardwareGpu("ranchu", "google/device", "Phone"))
        assertFalse(AssistantController.hasHardwareGpu("RANCHU", "google/device", "Phone"))
        assertFalse(AssistantController.hasHardwareGpu("Goldfish", "google/device", "Phone"))
        assertFalse(AssistantController.hasHardwareGpu("other", "generic/arm64", "Phone"))
        assertFalse(AssistantController.hasHardwareGpu("other", "GeNeRiC/arm64", "Phone"))
        assertFalse(AssistantController.hasHardwareGpu("other", "google/device", "sdk_gphone64_arm64"))
        assertFalse(AssistantController.hasHardwareGpu("other", "google/device", "SDK_GPHONE64_ARM64"))
        assertFalse(AssistantController.hasHardwareGpu("other", "google/device", "Android EmUlAtOr"))
    }

    @Test fun physicalHardwareKeepsGpuAndItsExistingFallback() {
        assertTrue(AssistantController.hasHardwareGpu("mt6893", "unihertz/Titan2/Titan2:user/release-keys", "Titan 2"))
        for (hardware in listOf("mt6878", "mt6789", "unihertz"))
            assertTrue(AssistantController.hasHardwareGpu(hardware, "unihertz/Titan2Elite:user/release-keys", "Titan 2 Elite"))
    }
}
