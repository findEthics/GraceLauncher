package com.galaxyrio.gracelauncher.assistant

/** Main-thread ownership of one submission, one inference, and at most one committed dispatch. */
internal class AssistantCommandSession {
    var generation = 0; private set
    var committed = false; private set
    private var claimed = false

    fun begin(): Int? {
        if (dispatchOwner != null) return null
        claimed = false
        return ++generation
    }

    fun claim(id: Int): Boolean {
        if (id != generation || claimed || committed) return false
        claimed = true
        return true
    }

    fun commit(id: Int): Boolean {
        if (id != generation || !claimed || committed || dispatchOwner != null) return false
        committed = true
        dispatchOwner = this
        return true
    }

    fun cancel(): Boolean {
        if (committed) return false
        ++generation
        claimed = false
        return true
    }

    fun finish() {
        if (dispatchOwner === this) dispatchOwner = null
        committed = false
    }

    companion object {
        // ponytail: one process-wide dispatch owner; HOME can recompose while an old write finishes.
        private var dispatchOwner: AssistantCommandSession? = null
    }
}
