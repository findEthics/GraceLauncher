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
    var noteSnapshot = emptyList<com.galaxyrio.gracelauncher.platform.SelectedNote>()
    val noteQueries = mutableListOf<String>()
    var saveResult = true
    val saves = mutableListOf<Triple<String, Boolean, Long?>>()
    val toasts = mutableListOf<String>()
    override fun hasContactAccess() = granted
    override fun installed(packageName: String) = packageName in installedPackages
    override fun contacts(provider: String?) = flowOf(contactSnapshots[provider].orEmpty())
    override suspend fun conversations(apps: List<LauncherApp>, automatic: Boolean, provider: String?) = conversationSnapshot
    override fun conversationEligible(conversation: MagicConversation) = conversation in conversationSnapshot
    override suspend fun notes(query: String) = noteSnapshot.also { noteQueries.add(query) }
    override suspend fun saveNoteOrTodo(text: String, todo: Boolean, noteId: Long?): Boolean {
        saves.add(Triple(text, todo, noteId))
        return saveResult
    }
    override fun onCaptureToast(message: String) { toasts.add(message) }
}
