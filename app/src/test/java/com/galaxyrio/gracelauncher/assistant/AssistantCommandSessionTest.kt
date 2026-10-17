package com.galaxyrio.gracelauncher.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantCommandSessionTest {
    @Test fun cancelledCaptureAndLateCallbacksCannotDispatch() {
        val session = AssistantCommandSession()
        val id = session.begin()!!
        assertTrue(session.cancel())
        assertFalse(session.claim(id))
        assertFalse(session.commit(id))
    }

    @Test fun duplicateResultsAndRepeatCommitExecuteOnlyOnce() {
        val session = AssistantCommandSession()
        val id = session.begin()!!
        assertFalse(session.commit(id))
        assertTrue(session.claim(id))
        assertFalse(session.claim(id))
        assertTrue(session.commit(id))
        assertFalse(session.commit(id))
        assertFalse(session.cancel()) // HOME pause cannot interrupt a committed write/read-back.
        assertNull(session.begin())
        session.finish()
        assertFalse(session.claim(id))
        assertNotNull(session.begin())
    }

    @Test fun cancellationDuringInferencePreventsCommit() {
        val session = AssistantCommandSession()
        val id = session.begin()!!
        assertTrue(session.claim(id))
        assertTrue(session.cancel())
        assertFalse(session.commit(id))
        val next = session.begin()!!
        assertTrue(session.claim(next))
        assertTrue(session.commit(next))
        session.finish()
    }

    @Test fun recreatedHomeCannotDispatchWhileOldHomeFinishes() {
        val old = AssistantCommandSession()
        val id = old.begin()!!
        assertTrue(old.claim(id)); assertTrue(old.commit(id))
        try {
            val recreated = AssistantCommandSession()
            assertNull(recreated.begin())
            recreated.cancel()
            assertTrue(old.committed)
        } finally { old.finish() }
        assertNotNull(AssistantCommandSession().begin())
    }
}
