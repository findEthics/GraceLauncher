package com.galaxyrio.gracelauncher.ui.overlays

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherOverlaySearchGateTest {
    @Test fun searchEntryUsesTheExistingDisabledSearchSettingsGate() {
        assertEquals(LauncherOverlay.Search, LauncherOverlay.search(true))
        assertEquals(LauncherOverlay.SettingsDestination("Search"), LauncherOverlay.search(false))
    }
}
