package com.finance.analyzer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.analyzer.config.LlmProperties;
import com.finance.analyzer.exception.AiProcessingException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Cloud provider: OpenAI (or any OpenAI-compatible server) via {@code POST {baseUrl}/chat/completions}. */
@Component
public class OpenAiLlmClient implements LlmClient {

    private static final int MAX_ERROR_CHARS = 300;

    private final LlmProperties.Endpoint cfg;
    private final int timeoutSeconds;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI uri;

    public OpenAiLlmClient(LlmProperties props) {
        this.cfg = props.openai();
        this.timeoutSeconds = props.timeoutSeconds();
        this.uri = URI.create(LlmHttp.stripTrailingSlash(cfg.baseUrl()) + "/chat/completions");
    }

    @Override public LlmProvider provider() { return LlmProvider.OPENAI; }

    @Override public String model() { return cfg.model(); }

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        try {
            HttpResponse<String> resp = post(systemPrompt, userPrompt, cfg.temperature());
            // Some models (o-series, gpt-5) only accept the default temperature and answer 400: send it once without.
            if (resp.statusCode() == 400 && cfg.temperature() != null && resp.body() != null
                    && resp.body().toLowerCase().contains("temperature")) {
                resp = post(systemPrompt, userPrompt, null);
            }
            if (resp.statusCode() != 200) {
                throw new AiProcessingException("OpenAI returned HTTP " + resp.statusCode() + " for model '" + cfg.model()
                        + "': " + abbreviate(resp.body()), null);
            }
            String content = mapper.readTree(resp.body()).path("choices").path(0).path("message").path("content").asText(null);
            if (!StringUtils.hasText(content)) {
                throw new AiProcessingException("OpenAI returned an empty reply for model '" + cfg.model() + "'.", null);
            }
            return content;
        } catch (AiProcessingException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProcessingException("Analysis was interrupted.", e);
        } catch (IOException | RuntimeException e) {
            String why = LlmHttp.isTimeout(e)
                    ? "no reply within " + timeoutSeconds + "s. Increase llm.timeout-seconds or lower analyzer.chunking.transactions-per-chunk"
                    : LlmHttp.describe(e);
            throw new AiProcessingException("OpenAI request failed (model '" + cfg.model() + "'): " + why, e);
        }
    }

    private HttpResponse<String> post(String systemPrompt, String userPrompt, Double temperature)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        if (temperature != null) body.put("temperature", temperature);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey().trim())
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > MAX_ERROR_CHARS ? t.substring(0, MAX_ERROR_CHARS) + "..." : t;
    }
}
