package com.finance.analyzer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.finance.analyzer.config.LlmProperties;
import com.finance.analyzer.exception.AiProcessingException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Cloud provider: OpenAI (or any OpenAI-compatible server) via {@code POST {baseUrl}/chat/completions}. */
@Component
public class OpenAiLlmClient implements LlmClient {

    private final LlmProperties.Endpoint cfg;
    private final RestClient rest;
    private final int timeoutSeconds;

    public OpenAiLlmClient(LlmProperties props) {
        this.cfg = props.openai();
        this.timeoutSeconds = props.timeoutSeconds();
        this.rest = LlmHttp.restClient(LlmHttp.stripTrailingSlash(cfg.baseUrl()), props.timeoutSeconds());
    }

    @Override public LlmProvider provider() { return LlmProvider.OPENAI; }

    @Override public String model() { return cfg.model(); }

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        if (cfg.temperature() != null) body.put("temperature", cfg.temperature());
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        try {
            JsonNode reply = rest.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .body(body).retrieve().body(JsonNode.class);
            String content = reply == null ? null : reply.path("choices").path(0).path("message").path("content").asText(null);
            if (!StringUtils.hasText(content)) {
                throw new AiProcessingException("OpenAI returned an empty reply for model '" + cfg.model() + "'.", null);
            }
            return content;
        } catch (AiProcessingException e) {
            throw e;
        } catch (RuntimeException e) {
            String why = LlmHttp.isTimeout(e)
                    ? "no reply within " + timeoutSeconds + "s. Increase llm.timeout-seconds or lower analyzer.chunking.transactions-per-chunk"
                    : LlmHttp.describe(e);
            throw new AiProcessingException("OpenAI request failed (model '" + cfg.model() + "'): " + why, e);
        }
    }
}
