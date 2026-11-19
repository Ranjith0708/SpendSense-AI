package com.finance.analyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code llm.*} block of application.yml.
 *
 * @param timeoutSeconds cap for a single OpenAI call
 * @param openai         OpenAI connection settings
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        @DefaultValue("120") int timeoutSeconds,
        @DefaultValue Endpoint openai) {

    /**
     * @param baseUrl     API root, e.g. https://api.openai.com/v1
     * @param apiKey      OpenAI API key (required)
     * @param model       chat model name
     * @param temperature sampling temperature; dropped automatically for models that only accept the default
     */
    public record Endpoint(
            @DefaultValue("https://api.openai.com/v1") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("gpt-4o-mini") String model,
            Double temperature) {}
}
