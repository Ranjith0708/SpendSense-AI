package com.finance.analyzer.llm;

/** Client for the chat-completions endpoint. Implementations must be thread-safe. */
public interface LlmClient {

    /** Human-readable model name, used for logging. */
    String model();

    /**
     * Sends one system + user prompt and returns the model's reply text, which is expected to be a JSON document.
     *
     * @throws com.finance.analyzer.exception.AiProcessingException if the endpoint is unreachable or replies with an error
     */
    String completeJson(String systemPrompt, String userPrompt);

    /**
     * Same as {@link #completeJson(String, String)} but with per-call limits. {@code null} means "use the configured
     * default". Providers that cannot honour a limit simply ignore it.
     *
     * @param maxOutputTokens cap on the reply length for this call
     * @param timeoutSeconds  cap on the whole call for this call
     */
    default String completeJson(String systemPrompt, String userPrompt, Integer maxOutputTokens, Integer timeoutSeconds) {
        return completeJson(systemPrompt, userPrompt);
    }
}
