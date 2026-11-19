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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Local provider: Ollama's native {@code /api/chat} (CodeLlama, Llama, Qwen, ...).
 *
 * <p>The reply is streamed, like the {@code ollama run} command line does. A slow model is therefore not mistaken for
 * a hung one, and {@code llm.timeout-seconds} is a cap on the whole call, enforced by a watchdog even if Ollama stalls.
 * The request also sets a context window and an output-token cap, because Ollama's defaults are small and a small
 * model in JSON mode can otherwise generate until it runs out of context.
 */
@Component
@Slf4j
public class OllamaLlmClient implements LlmClient {

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ollama-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final LlmProperties.Endpoint cfg;
    private final int timeoutSeconds;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI uri;

    public OllamaLlmClient(LlmProperties props) {
        this.cfg = props.local();
        this.timeoutSeconds = props.timeoutSeconds();
        this.uri = URI.create(LlmHttp.stripTrailingSlash(cfg.baseUrl()) + "/api/chat");
    }

    @Override public LlmProvider provider() { return LlmProvider.LOCAL; }

    @Override public String model() { return cfg.model(); }

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        return completeJson(systemPrompt, userPrompt, null, null);
    }

    @Override
    public String completeJson(String systemPrompt, String userPrompt, Integer maxOutputTokens, Integer timeoutOverride) {
        final int timeoutSeconds = timeoutOverride != null && timeoutOverride > 0
                ? Math.min(timeoutOverride, this.timeoutSeconds) : this.timeoutSeconds;
        final Integer numPredict = maxOutputTokens != null && maxOutputTokens > 0 ? maxOutputTokens : cfg.maxOutputTokens();
        Map<String, Object> options = new LinkedHashMap<>();
        if (cfg.temperature() != null) options.put("temperature", cfg.temperature());
        if (cfg.contextLength() != null) options.put("num_ctx", cfg.contextLength());
        if (numPredict != null) options.put("num_predict", numPredict);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("stream", true);
        body.put("format", "json");
        body.put("keep_alive", "30m");          // keep the model loaded between chunks
        if (!options.isEmpty()) body.put("options", options);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));

        long start = System.nanoTime();
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> guard = null;
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))   // covers connect + time to first response headers
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (StringUtils.hasText(cfg.apiKey())) rb.header("Authorization", "Bearer " + cfg.apiKey());

            HttpResponse<Stream<String>> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofLines());
            Stream<String> lines = resp.body();
            guard = WATCHDOG.schedule(() -> { timedOut.set(true); lines.close(); },
                    Math.max(1, timeoutSeconds - (System.nanoTime() - start) / 1_000_000_000L), TimeUnit.SECONDS);

            if (resp.statusCode() != 200) {
                String text = String.join(" ", lines.limit(20).toList());
                throw new AiProcessingException("Ollama returned HTTP " + resp.statusCode() + " for model '" + cfg.model()
                        + "': " + text + (resp.statusCode() == 404 ? " (is the model pulled? try: ollama pull " + cfg.model() + ")" : ""), null);
            }

            StringBuilder out = new StringBuilder();
            JsonNode last = null;
            boolean jsonComplete = false;
            JsonObjectEnd objectEnd = new JsonObjectEnd();
            try (lines) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line.isBlank()) continue;
                    JsonNode n = mapper.readTree(line);
                    if (n.hasNonNull("error")) {
                        throw new AiProcessingException("Ollama error for model '" + cfg.model() + "': " + n.get("error").asText(), null);
                    }
                    String piece = n.path("message").path("content").asText("");
                    out.append(piece);
                    if (n.path("done").asBoolean(false)) { last = n; break; }
                    // A small model in JSON mode often finishes the object and then keeps emitting whitespace/tokens until
                    // the token cap or timeout. The reply is already complete once the top-level object closes, so stop
                    // here; closing the stream (try-with-resources) tells Ollama to stop generating.
                    if (objectEnd.feed(piece)) { jsonComplete = true; break; }
                }
            }
            if (jsonComplete) {
                log.info("ollama_done model={} durationMs={} stoppedEarly=json_object_complete replyChars={}",
                        cfg.model(), (System.nanoTime() - start) / 1_000_000, out.length());
            } else {
                if (timedOut.get() || last == null) {
                    throw new AiProcessingException(timedOut.get() ? timeoutMessage(timeoutSeconds) : "Ollama closed the stream before finishing the reply (model '"
                            + cfg.model() + "'). Is the model out of memory? Check the Ollama server log.", null);
                }
                String reason = last.path("done_reason").asText("");
                log.info("ollama_done model={} durationMs={} promptTokens={} outputTokens={} doneReason={} replyChars={}",
                        cfg.model(), (System.nanoTime() - start) / 1_000_000, last.path("prompt_eval_count").asInt(-1),
                        last.path("eval_count").asInt(-1), reason, out.length());
                if ("length".equals(reason)) {
                    log.warn("ollama_reply_truncated model={} the reply hit the output cap (num_predict={}); "
                            + "use a smaller analyzer.chunking.transactions-per-chunk or raise the cap", cfg.model(), numPredict);
                }
            }
            if (out.toString().isBlank()) {
                throw new AiProcessingException("Ollama returned an empty reply for model '" + cfg.model() + "'.", null);
            }
            return out.toString();
        } catch (AiProcessingException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProcessingException("Analysis was interrupted.", e);
        } catch (IOException | RuntimeException e) {
            if (timedOut.get() || LlmHttp.isTimeout(e)) throw new AiProcessingException(timeoutMessage(timeoutSeconds), e);
            throw new AiProcessingException("Could not talk to Ollama at " + cfg.baseUrl() + " (model '" + cfg.model()
                    + "'). Check that it is running: " + LlmHttp.describe(e), e);
        } finally {
            if (guard != null) guard.cancel(false);
        }
    }

    private String timeoutMessage(int seconds) {
        return "Ollama did not finish within " + seconds + "s (model '" + cfg.model()
                + "'). Use a smaller analyzer.chunking.transactions-per-chunk, a faster model, or raise llm.timeout-seconds.";
    }

    /** Detects the point where the first top-level JSON object in a streamed reply is closed (string/escape aware). */
    static final class JsonObjectEnd {
        private int depth;
        private boolean started, inString, escaped;

        /** Feeds the next piece of text; returns true as soon as the outermost object has been closed. */
        boolean feed(String piece) {
            for (int i = 0; i < piece.length(); i++) {
                char c = piece.charAt(i);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                } else if (c == '"') {
                    if (started) inString = true;
                } else if (c == '{') {
                    started = true;
                    depth++;
                } else if (c == '}' && started && --depth == 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
