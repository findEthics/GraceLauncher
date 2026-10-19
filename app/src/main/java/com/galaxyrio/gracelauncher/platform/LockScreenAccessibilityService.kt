package com.galaxyrio.gracelauncher.platform

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/** Performs the single system action requested by the user's double-tap gesture. */
class LockScreenAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        currentService = WeakReference(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearServiceReference()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearServiceReference()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun clearServiceReference() {
        if (currentService?.get() === this) currentService = null
    }

    companion object {
        @Volatile private var currentService: WeakReference<LockScreenAccessibilityService>? = null

        fun lockScreen(context: Context): Boolean {
            if (!isEnabled(context)) return false
            val service = currentService?.get() ?: return false
            if (!isEnabled(context)) return false
            return try {
                service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } catch (_: RuntimeException) {
                false
            }
        }

        private fun isEnabled(context: Context): Boolean {
            if (Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
            val component = ComponentName(context, LockScreenAccessibilityService::class.java)
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
