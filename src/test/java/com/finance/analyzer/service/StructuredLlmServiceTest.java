package com.finance.analyzer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.finance.analyzer.config.AnalyzerProperties;
import com.finance.analyzer.config.LlmProperties;
import com.finance.analyzer.exception.AiProcessingException;
import com.finance.analyzer.llm.LlmClient;
import com.finance.analyzer.llm.LlmClientFactory;
import com.finance.analyzer.llm.LlmProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class StructuredLlmServiceTest {

    private static final AnalyzerProperties PROPS = new AnalyzerProperties(
            new AnalyzerProperties.Chunking(10, 8000), new AnalyzerProperties.Processing(1, 3, 0, 1),
            new AnalyzerProperties.Logging(false));

    /** Records every call (tokens and timeout limits) and replies with whatever {@code reply} supplies. */
    private static final class Recorder implements LlmClient {
        final List<String> calls = new ArrayList<>();
        final Supplier<String> reply;
        Integer lastTokens, lastTimeout;

        Recorder(Supplier<String> reply) { this.reply = reply; }

        public LlmProvider provider() { return LlmProvider.LOCAL; }
        public String model() { return "scripted"; }
        public String completeJson(String system, String user) { return completeJson(system, user, null, null); }
        @Override public String completeJson(String system, String user, Integer maxOutputTokens, Integer timeoutSeconds) {
            calls.add(user);
            lastTokens = maxOutputTokens;
            lastTimeout = timeoutSeconds;
            return reply.get();
        }
    }

    private static StructuredLlmService service(LlmClient client) {
        LlmProperties lp = new LlmProperties("local", 30, new LlmProperties.Endpoint("http://x", "", "m", null, null, null),
                new LlmProperties.Endpoint("http://y", "", "m", null, null, null));
        LlmClientFactory factory = new LlmClientFactory(List.of(client), lp);
        factory.init();
        return new StructuredLlmService(factory, PROPS);
    }

    @Test void defaultCallRetriesUpToConfiguredAttemptsWithNoPerCallLimits() {
        Recorder rec = new Recorder(() -> { throw new AiProcessingException("boom", null); });
        AiProcessingException e = assertThrows(AiProcessingException.class,
                () -> service(rec).call("s", "u", JsonNode.class, "t"));
        assertEquals(3, rec.calls.size());
        assertNull(rec.lastTokens);
        assertNull(rec.lastTimeout);
        assertEquals(true, e.getMessage().contains("after 3 attempts"));
    }

    @Test void perCallLimitsArePassedToTheClientAndOnlyOneAttemptIsMade() {
        Recorder rec = new Recorder(() -> { throw new AiProcessingException("timeout", null); });
        assertThrows(AiProcessingException.class, () -> service(rec).call("s", "u", JsonNode.class, "narrative", 1, 1024, 180));
        assertEquals(1, rec.calls.size());
        assertEquals(1024, rec.lastTokens);
        assertEquals(180, rec.lastTimeout);
    }

    @Test void attemptsAreNeverAboveTheConfiguredMaximum() {
        Recorder rec = new Recorder(() -> { throw new AiProcessingException("x", null); });
        assertThrows(AiProcessingException.class, () -> service(rec).call("s", "u", JsonNode.class, "t", 99, null, null));
        assertEquals(3, rec.calls.size());
    }

    @Test void jsonWrappedInProseOrFencesIsParsed() {
        Recorder rec = new Recorder(() -> "Sure! ```json\n{\"summary\":\"ok\"}\n``` hope that helps");
        JsonNode n = service(rec).call("s", "u", JsonNode.class, "t");
        assertEquals("ok", n.get("summary").asText());
    }

    @Test void replyWithoutJsonIsAFailure() {
        Recorder rec = new Recorder(() -> "no json here");
        assertThrows(AiProcessingException.class, () -> service(rec).call("s", "u", JsonNode.class, "t", 1, null, null));
    }
}
