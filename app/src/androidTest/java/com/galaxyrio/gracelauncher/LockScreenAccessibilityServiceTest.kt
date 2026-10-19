package com.galaxyrio.gracelauncher

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyrio.gracelauncher.platform.LockScreenAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockScreenAccessibilityServiceTest {
    @Test
    fun serviceBindingIsProtectedAndRequestsNoScreenOrTouchExplorationAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val info = manager.installedAccessibilityServiceList.singleOrNull {
            val service = it.resolveInfo.serviceInfo
            service.packageName == context.packageName && service.name == LockScreenAccessibilityService::class.java.name
        }
        assertNotNull("The lock-screen service must be discoverable in Accessibility settings", info)
        val serviceInfo = requireNotNull(info)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", serviceInfo.resolveInfo.serviceInfo.permission)
        assertEquals(0, serviceInfo.eventTypes)
        assertFalse(serviceInfo.canRetrieveWindowContent)
        assertEquals(0, serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
        assertEquals(0, serviceInfo.capabilities)
        if (Build.VERSION.SDK_INT >= 31) assertFalse(serviceInfo.isAccessibilityTool)
    }
}
