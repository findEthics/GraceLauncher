package com.galaxyrio.gracelauncher.magic

import android.content.Context
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.SearchContact
import com.galaxyrio.gracelauncher.data.SearchContacts
import kotlinx.coroutines.flow.Flow

/** Per-controller source injection; synthetic fixtures cannot dispatch external actions. */
internal interface MagicDataSource {
    val externalActionsAllowed: Boolean get() = false
    fun hasContactAccess(): Boolean
    fun installed(packageName: String): Boolean
    fun contacts(provider: String?): Flow<List<SearchContact>>
    suspend fun conversations(apps: List<LauncherApp>, automatic: Boolean, provider: String?): List<MagicConversation>
    fun conversationEligible(conversation: MagicConversation): Boolean
}

internal class PlatformMagicDataSource(context: Context, private val actions: MagicActions) : MagicDataSource {
    private val generic = SearchContacts(context)
    private val synced = ProviderSyncedContacts(context)
    override val externalActionsAllowed = true
    override fun hasContactAccess() = generic.hasAccess()
    override fun installed(packageName: String) = synced.installed(packageName)
    override fun contacts(provider: String?) = strictContactProvider(provider)?.let(synced::observe) ?: generic.observe()
    override suspend fun conversations(apps: List<LauncherApp>, automatic: Boolean, provider: String?) =
        actions.conversations(apps, actions.messaging.recentConversationPackages(automatic, provider))
    override fun conversationEligible(conversation: MagicConversation) = actions.conversationEligible(conversation)
}
