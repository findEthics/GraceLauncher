package com.galaxyrio.gracelauncher.magic

import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.SearchContact
import kotlinx.coroutines.flow.flowOf

/** Per-test snapshots. No Contacts observer, shortcut query, or external dispatch. */
internal class SyntheticMagicDataSource : MagicDataSource {
    var granted = true
    var installedPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    var contactSnapshots = emptyMap<String?, List<SearchContact>>()
    var conversationSnapshot = emptyList<MagicConversation>()
    override fun hasContactAccess() = granted
    override fun installed(packageName: String) = packageName in installedPackages
    override fun contacts(provider: String?) = flowOf(contactSnapshots[provider].orEmpty())
    override suspend fun conversations(apps: List<LauncherApp>, automatic: Boolean, provider: String?) = conversationSnapshot
    override fun conversationEligible(conversation: MagicConversation) = conversation in conversationSnapshot
}
