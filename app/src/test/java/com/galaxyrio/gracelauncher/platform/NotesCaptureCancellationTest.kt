package com.galaxyrio.gracelauncher.platform

import android.content.ContentResolver
import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NotesCaptureCancellationTest {
    private val cancellation = CancellationException("Capture cancelled")
    private val context = object : ContextWrapper(null) {
        override fun getContentResolver(): ContentResolver = throw cancellation
    }

    @Test
    fun listNotesRethrowsCancellation() = runBlocking {
        try {
            NotesCaptureContract.listNotes(context, null)
            fail("listNotes swallowed cancellation")
        } catch (e: CancellationException) {
            assertEquals(cancellation.message, e.message)
        }
    }

    @Test
    fun saveRethrowsCancellation() = runBlocking {
        try {
            NotesCaptureContract.save(context, "Test note", todo = false, noteId = null)
            fail("save swallowed cancellation")
        } catch (e: CancellationException) {
            assertEquals(cancellation.message, e.message)
        }
    }
}
