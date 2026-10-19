package com.galaxyrio.gracelauncher.data

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkProfilePolicyTest {
    @Test fun platformClassificationIncludesOnlyManagedProfilesOnAndroid15AndLater() {
        val android15 = Build.VERSION_CODES.VANILLA_ICE_CREAM
        assertTrue(isWorkProfile(android15, isCurrentUser = false, isManagedProfile = true))
        assertFalse(isWorkProfile(android15, isCurrentUser = false, isManagedProfile = false))
        assertFalse(isWorkProfile(android15, isCurrentUser = true, isManagedProfile = true))
        assertTrue(isWorkProfile(android15 - 1, isCurrentUser = false, isManagedProfile = false))
    }

    @Test fun profileKeyFilteringKeepsKnownWorkDataAndHidesPrivateOrUnknownProfiles() {
        assertEquals(42L, profileSerialFromKey("profile:42:shortcut:com.example/app"))
        assertNull(profileSerialFromKey("com.example/.Main"))
        assertNull(profileSerialFromKey("profile:unknown:shortcut:com.example/app"))
        assertFalse(isPrivateOrUnknownProfileKey("profile:42:com.example/.Main", setOf(42L)))
        assertTrue(isPrivateOrUnknownProfileKey("profile:43:com.example/.Main", setOf(42L)))
        assertTrue(isPrivateOrUnknownProfileKey("profile:unknown:shortcut:com.example/app", setOf(42L)))
    }
}
