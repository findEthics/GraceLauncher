package com.galaxyrio.gracelauncher.assistant

import java.util.Locale

/** Rows of the chat `/` popup, in display order. Aliases match but never get their own row. */
internal enum class SlashCommand(val token: String, val aliases: List<String>) {
    WEB("web", emptyList()), WIKIPEDIA("wikipedia", listOf("wiki")), HEALTH("health", emptyList()),
    REMEMBER("remember", listOf("save")), RECALL("recall", emptyList()),
    FORGET("forget", listOf("delete")), PURGE("purge", listOf("erase")), HISTORY("history", emptyList()),
}

private val TRIGGER = Regex("^/[A-Za-z]*$")

/** Rows for the popup, or empty when it must be hidden (anything but a lone `/word` composer text). */
internal fun slashSuggestions(input: String, healthAvailable: Boolean): List<SlashCommand> {
    if (!TRIGGER.matches(input)) return emptyList()
    val prefix = input.drop(1).lowercase(Locale.ROOT)
    return SlashCommand.entries.filter { c ->
        (c != SlashCommand.HEALTH || healthAvailable) && (listOf(c.token) + c.aliases).any { it.startsWith(prefix) }
    }
}

private val SOURCE_PREFIX = Regex("^/(\\S+)\\s+(.*)$", RegexOption.DOT_MATCHES_ALL)

/** Leading `/web|/wikipedia|/wiki|/health` + whitespace + rest → (source, rest); `/health` needs a saved token. */
internal fun parseSourcePrefix(input: String, healthAvailable: Boolean): Pair<ChatSource, String>? {
    val m = SOURCE_PREFIX.matchEntire(input.trimStart()) ?: return null
    val source = when (m.groupValues[1].lowercase(Locale.ROOT)) {
        "web" -> ChatSource.WEB
        "wikipedia", "wiki" -> ChatSource.WIKIPEDIA
        "health" -> if (healthAvailable) ChatSource.HEALTH else return null
        else -> return null
    }
    return source to m.groupValues[2].trim()
}
