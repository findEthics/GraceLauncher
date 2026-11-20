package com.galaxyrio.gracelauncher.assistant.memory

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySqlTest {
    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

    @Test fun inAppQueriesAreContainedInManualDeleteSql() {
        // Gradle runs unit tests from the module dir (app/); fall back to the repo root for other runners.
        val file = listOf("../server/supabase/grace_memory/manual-delete.sql", "server/supabase/grace_memory/manual-delete.sql")
            .map(::File).first { it.exists() }
        val sql = norm(file.readText())
        for ((name, q) in listOf("LIST" to MemorySql.LIST, "PURGE_ONE" to MemorySql.PURGE_ONE,
            "PURGE_ALL" to MemorySql.PURGE_ALL, "TOMBSTONES" to MemorySql.TOMBSTONES)) {
            assertTrue("$name drifted from manual-delete.sql", sql.contains(norm(q)))
        }
    }
}
