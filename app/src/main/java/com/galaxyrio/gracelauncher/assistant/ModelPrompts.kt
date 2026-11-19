package com.galaxyrio.gracelauncher.assistant

internal fun modelRetrievalInstructions(model: AssistantModel, system: String, source: ChatSource, firstReply: Boolean = true): String {
    if (model != AssistantModel.Qwen2_5_1_5B) return system
    val dates = "Preserve every explicit year and named entity from the question in the search query and answer. " +
        "For a winner in a requested year, report that year's winner, not the latest winner. " +
        "Evidence from a different year does not answer the question.\n"
    if (!firstReply) return dates + system
    val tool = when (source) {
        ChatSource.WEB -> "search_web"
        ChatSource.WIKIPEDIA -> "query_wikipedia"
        else -> return system
    }
    val example = if (source == ChatSource.WEB) "<|tool_call>call:search_web>population of Paris" else
        """<|tool_call>call:query_wikipedia>{"topic":"Paris","lang":"en","focus":"population"}"""
    val arguments = if (source == ChatSource.WIKIPEDIA)
        "query_wikipedia requires exactly three string arguments: topic (article topic), lang (two-letter question language), focus (requested facts). " else ""
    return dates + "For factual questions, you must call $tool before answering. " +
        "Your first response must contain only the tool request, never an answer from memory. " +
        arguments +
        "If native function calling is unavailable, output exactly this text format, substituting the user's topic, language and facts: " +
        example + "\nThis is only a format example, not the user's topic. Do not add quotes, code fences or commentary. " +
        "After the tool returns, answer only from its evidence.\n" + system
}

internal fun modelSavedInstructions(model: AssistantModel, system: String): String =
    if (model == AssistantModel.Gemma4 || model == AssistantModel.Gemma4E4B)
        system + "\nRepeat each source's exact Headline in your answer, followed by its summary and numbered citation."
    else system
