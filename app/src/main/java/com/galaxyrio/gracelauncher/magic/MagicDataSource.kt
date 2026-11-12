package com.galaxyrio.gracelauncher.magic

import android.content.Context
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.SearchContact
import com.galaxyrio.gracelauncher.data.SearchContacts
import com.galaxyrio.gracelauncher.platform.NotesCaptureContract
import com.galaxyrio.gracelauncher.platform.SelectedNote
import kotlinx.coroutines.flow.Flow

/** Per-controller source injection; synthetic fixtures cannot dispatch external actions. */
internal interface MagicDataSource {
    val externalActionsAllowed: Boolean get() = false
    fun hasContactAccess(): Boolean
    fun installed(packageName: String): Boolean
    fun contacts(provider: String?): Flow<List<SearchContact>>
    fun effectivePreferredPackage(provider: String?): String? = provider
    suspend fun conversations(apps: List<LauncherApp>, automatic: Boolean, provider: String?): List<MagicConversation>
    fun conversationEligible(conversation: MagicConversation): Boolean
    suspend fun notes(query: String): List<SelectedNote>? = emptyList()
    suspend fun saveNoteOrTodo(text: String, todo: Boolean, noteId: Long?): Boolean
    fun onCaptureToast(message: String) {}
}

internal class PlatformMagicDataSource(private val context: Context, private val actions: MagicActions) : MagicDataSource {
    private val generic = SearchContacts(context)
    private val synced = ProviderSyncedContacts(context)
    override val externalActionsAllowed = true
    override fun hasContactAccess() = generic.hasAccess()
    override fun installed(packageName: String) = synced.installed(packageName)
    override fun contacts(provider: String?) = strictContactProvider(provider)?.let(synced::observe) ?: generic.observe()
    override fun effectivePreferredPackage(provider: String?) = provider ?: actions.messaging.defaultMessagingPackage()
    override suspend fun conversations(apps: List<LauncherApp>, automatic: Boolean, provider: String?) =
        actions.conversations(apps, actions.messaging.recentConversationPackages(automatic, provider), effectivePreferredPackage(provider))
    override fun conversationEligible(conversation: MagicConversation) = actions.conversationEligible(conversation)
    override suspend fun notes(query: String) = NotesCaptureContract.listNotes(context, query)
    override suspend fun saveNoteOrTodo(text: String, todo: Boolean, noteId: Long?) =
        NotesCaptureContract.save(context, text, todo, noteId)
}
