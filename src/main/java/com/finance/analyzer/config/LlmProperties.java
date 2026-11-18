package com.finance.analyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code llm.*} block of application.yml.
 *
 * @param provider       active provider: {@code local} (Ollama / CodeLlama) or {@code openai}
 *                       ({@code dev} and {@code cloud} are accepted aliases for {@code openai})
 * @param timeoutSeconds read timeout for a single LLM call
 * @param local          settings for the local Ollama endpoint
 * @param openai         settings for the OpenAI (or any OpenAI-compatible) endpoint
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        @DefaultValue("local") String provider,
        @DefaultValue("180") int timeoutSeconds,
        @DefaultValue Endpoint local,
        @DefaultValue Endpoint openai) {

    /**
     * @param baseUrl     endpoint root
     * @param apiKey      required for OpenAI, optional for local (sent as a bearer token only when non-blank)
     * @param model       model name
     * @param temperature sampling temperature; leave unset for models that only accept the default
     * @param contextLength  local only: Ollama {@code num_ctx}, tokens of prompt + reply the model may hold
     * @param maxOutputTokens local only: Ollama {@code num_predict}, hard cap on reply length (stops runaway generation)
     */
    public record Endpoint(
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String model,
            Double temperature,
            Integer contextLength,
            Integer maxOutputTokens) {}
}
