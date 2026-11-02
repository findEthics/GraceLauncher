// Adapted for Grace: standalone RAM-only shortcut identity. See third_party/openminilaunch.
package com.katoaapps.openminilaunch.model

/** An app-published static, dynamic, cached, or launcher-pinned shortcut. */
data class LauncherShortcutTarget(
    val label: String,
    val packageName: String,
    val shortcutId: String,
    val userSerial: Long,
    val isWorkProfile: Boolean,
    val isAvailable: Boolean = true,
    val selectionKey: String = "$userSerial:$packageName:$shortcutId",
)
