package com.galaxyrio.gracelauncher.platform

import android.content.Context
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SelectedNote(val id: Long, val title: String)

internal object NotesCaptureContract {
    const val AUTHORITY = "com.example.notes.capture"
    val URI: Uri by lazy { Uri.parse("content://$AUTHORITY") }

    suspend fun listNotes(context: Context, query: String?): List<SelectedNote>? = withContext(Dispatchers.IO) {
        try {
            val result = context.contentResolver.call(URI, "list_notes", query, Bundle().apply { putInt("limit", 20) })
            if (result?.getBoolean("ok") != true) return@withContext null
            val ids = result.getLongArray("ids") ?: return@withContext null
            val titles = result.getStringArrayList("titles") ?: return@withContext null
            if (ids.size != titles.size) return@withContext null
            ids.indices.map { SelectedNote(ids[it], titles[it]) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) { null }
    }

    suspend fun save(context: Context, text: String, todo: Boolean, noteId: Long?): Boolean = withContext(Dispatchers.IO) {
        try {
            val method = if (todo) "insert_task" else if (noteId == null) "create_note" else "append_note"
            val extras = Bundle().apply {
                putString("text", text)
                if (noteId != null) putLong("id", noteId)
            }
            context.contentResolver.call(URI, method, null, extras)?.getBoolean("ok") == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) { false }
    }
}
