package com.katoaapps.openminilaunch.features.messaging

import com.katoaapps.openminilaunch.model.LauncherShortcutTarget

/** WhatsApp selections are strict in manual and automatic mode; other discovery remains unchanged. */
internal fun resolveRecentConversationPackages(
    sendAutomatically: Boolean,
    preferredPackage: String?,
    defaultSmsPackage: String?,
    isInstalled: (String) -> Boolean,
): Set<String> {
    if (preferredPackage == "com.whatsapp" || preferredPackage == "com.whatsapp.w4b") {
        return setOfNotNull(preferredPackage.takeIf(isInstalled))
    }
    val installedDefault = defaultSmsPackage?.takeIf(isInstalled)
    if (sendAutomatically) {
        val installedPreference = preferredPackage?.takeIf(isInstalled)
        return setOfNotNull(installedPreference ?: installedDefault)
    }

    return buildSet {
        installedDefault?.let(::add)
        MessagingProviderCatalog.providers
            .flatMap { it.packageNames }
            .filter(isInstalled)
            .forEach(::add)
    }
}

internal fun filteredRecentConversations(
    shortcuts: List<LauncherShortcutTarget>,
    query: String,
    appLabel: (String) -> String,
    prioritizedPackage: String? = null,
    limit: Int = 5,
): List<LauncherShortcutTarget> {
    val normalizedQuery = query.trim()
    return shortcuts.asSequence()
        .filter { shortcut ->
            normalizedQuery.isEmpty() ||
                shortcut.label.contains(normalizedQuery, ignoreCase = true) ||
                appLabel(shortcut.packageName).contains(normalizedQuery, ignoreCase = true)
        }
        .sortedBy { it.packageName != prioritizedPackage }
        .take(limit)
        .toList()
}
