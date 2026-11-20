package com.galaxyrio.gracelauncher.assistant.memory

import java.util.Locale

internal enum class MemoryChip { SAVE, RECALL, DELETE, PURGE }

internal sealed interface MemoryTarget {
    data class Words(val text: String) : MemoryTarget
    data class Index(val n: Int) : MemoryTarget
}

internal sealed interface MemoryCommand {
    data class Remember(val text: String) : MemoryCommand
    data class Delete(val target: MemoryTarget?) : MemoryCommand
    data class Erase(val target: MemoryTarget?) : MemoryCommand
    data class Recall(val query: String) : MemoryCommand
    data class History(val n: Int?) : MemoryCommand
}

private val SLASH = Regex("^/(\\S+)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

private fun target(arg: String): MemoryTarget? {
    if (arg.isBlank()) return null
    val n = arg.takeIf { it.length <= 2 && it.all(Char::isDigit) }?.toIntOrNull()
    return if (n != null && n >= 1) MemoryTarget.Index(n) else MemoryTarget.Words(arg)
}

/**
 * Pre-model parse. A known slash token wins over [chip]; an unknown `/xyz` is ordinary text
 * (so a chip, if any, still applies to the whole text). Returns null when no memory command applies.
 */
internal fun parseMemoryCommand(text: String, chip: MemoryChip? = null): MemoryCommand? {
    val t = text.trim()
    SLASH.matchEntire(t)?.let { m ->
        val arg = m.groupValues[2].trim()
        when (m.groupValues[1].lowercase(Locale.ROOT)) {
            "remember", "save" -> return MemoryCommand.Remember(arg)
            "delete", "forget" -> return MemoryCommand.Delete(target(arg))
            "erase", "purge" -> return MemoryCommand.Erase(target(arg))
            "recall" -> return MemoryCommand.Recall(arg)
            "history" -> return MemoryCommand.History(arg.toIntOrNull()?.takeIf { it >= 1 })
        }
    }
    if (chip == MemoryChip.RECALL) return MemoryCommand.Recall(t)
    if (chip == null || t.isEmpty()) return null
    return when (chip) {
        MemoryChip.SAVE -> MemoryCommand.Remember(t)
        MemoryChip.RECALL -> MemoryCommand.Recall(t)
        MemoryChip.DELETE -> MemoryCommand.Delete(target(t))
        MemoryChip.PURGE -> MemoryCommand.Erase(target(t))
    }
}
