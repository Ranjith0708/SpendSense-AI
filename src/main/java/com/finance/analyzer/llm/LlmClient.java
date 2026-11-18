package com.finance.analyzer.llm;

/** Strategy interface: one implementation per provider. Implementations must be thread-safe. */
public interface LlmClient {

    LlmProvider provider();

    /** Human-readable model name, used for logging. */
    String model();

    /**
     * Sends one system + user prompt and returns the model's reply text, which is expected to be a JSON document.
     *
     * @throws com.finance.analyzer.exception.AiProcessingException if the endpoint is unreachable or replies with an error
     */
    String completeJson(String systemPrompt, String userPrompt);
}
