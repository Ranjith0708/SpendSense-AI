package com.finance.analyzer.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.finance.analyzer.config.AnalyzerProperties;
import com.finance.analyzer.exception.AiProcessingException;
import com.finance.analyzer.llm.LlmClient;
import com.finance.analyzer.llm.LlmClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Calls the active LLM and maps its JSON reply onto a Java type, with rate limiting, retries and logging.
 * Which provider is used is decided solely by {@link LlmClientFactory}.
 */
@Service
@Slf4j
public class StructuredLlmService {

    private final LlmClientFactory factory;
    private final int maxAttempts;
    private final RateLimiter rateLimiter;
    private final boolean logPayloads;
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    public StructuredLlmService(LlmClientFactory factory, AnalyzerProperties props) {
        this.factory = factory;
        this.maxAttempts = Math.max(1, props.processing().maxAttempts());
        this.logPayloads = props.logging().logPayloads();
        this.rateLimiter = new RateLimiter(props.processing().minIntervalMs());
    }

    /** Payloads hold statement data: INFO when {@code analyzer.logging.log-payloads} is on, otherwise DEBUG. */
    private void payload(String msg, Object... args) {
        if (logPayloads) log.info(msg, args); else log.debug(msg, args);
    }

    /**
     * @param label short tag for logs, e.g. {@code chunk_3} or {@code narrative}
     */
    public <T> T call(String systemPrompt, String userPrompt, Class<T> type, String label) {
        LlmClient client = factory.active();
        AiProcessingException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.nanoTime();
            String stage = "request";
            String raw = null;
            try {
                rateLimiter.acquire();
                payload("llm_input label={} provider={} model={} attempt={}/{} promptChars={}\n{}",
                        label, client.provider(), client.model(), attempt, maxAttempts, userPrompt.length(), userPrompt);
                raw = client.completeJson(systemPrompt, userPrompt);
                long ms = (System.nanoTime() - start) / 1_000_000;
                payload("llm_raw_output label={} attempt={} durationMs={}\n{}", label, attempt, ms, raw);
                stage = "parse";
                T parsed = mapper.readValue(extractJson(raw), type);
                if (parsed == null) throw new IllegalStateException("model returned null JSON");
                log.info("llm_call_ok label={} provider={} attempt={} durationMs={}", label, client.provider(), attempt, ms);
                return parsed;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiProcessingException("Analysis was interrupted.", e);
            } catch (AiProcessingException e) {
                last = e;
                log.warn("llm_call_failed label={} attempt={}/{} durationMs={} reason={}", label, attempt, maxAttempts,
                        (System.nanoTime() - start) / 1_000_000, e.getMessage());
            } catch (Exception e) {
                // Message can be null (e.g. timeouts), so always log the exception class and cause too.
                String detail = e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                last = new AiProcessingException("Model " + stage + " failed: " + detail, e);
                log.warn("llm_{}_failed label={} attempt={}/{} durationMs={} error={}", stage, label, attempt, maxAttempts,
                        (System.nanoTime() - start) / 1_000_000, detail, e);
                if (raw != null) log.warn("llm_unparseable_output label={} attempt={}\n{}", label, attempt, raw);
            }
        }
        throw new AiProcessingException("The AI service could not process " + label + " after " + maxAttempts
                + " attempts. " + (last == null ? "" : last.getMessage()), last);
    }

    /** Models sometimes wrap JSON in code fences or add prose; keep only the outermost object. */
    static String extractJson(String raw) {
        if (raw == null) throw new IllegalStateException("empty reply");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("no JSON object in reply");
        return raw.substring(start, end + 1);
    }
}
