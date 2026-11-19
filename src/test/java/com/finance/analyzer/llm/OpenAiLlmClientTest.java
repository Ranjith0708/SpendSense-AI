package com.finance.analyzer.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.analyzer.config.LlmProperties;
import com.finance.analyzer.exception.AiProcessingException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Talks to a throw-away local HTTP server that imitates {@code POST /v1/chat/completions}. */
class OpenAiLlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Req(String auth, String contentType, JsonNode body) {}

    private record Reply(int status, String body) {}

    private static String completion(String content) throws IOException {
        return MAPPER.writeValueAsString(java.util.Map.of("choices",
                List.of(java.util.Map.of("message", java.util.Map.of("role", "assistant", "content", content)))));
    }

    private static HttpServer start(List<Req> seen, Function<Req, Reply> handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            JsonNode body = MAPPER.readTree(ex.getRequestBody().readAllBytes());
            Req req = new Req(ex.getRequestHeaders().getFirst("Authorization"),
                    ex.getRequestHeaders().getFirst("Content-Type"), body);
            seen.add(req);
            Reply reply = handler.apply(req);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(reply.status(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return server;
    }

    private static OpenAiLlmClient client(HttpServer server, String baseSuffix, String key, Double temperature) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1" + baseSuffix;
        LlmProperties lp = new LlmProperties(20, new LlmProperties.Endpoint(url, key, "gpt-test", temperature));
        return new OpenAiLlmClient(lp);
    }

    @Test void sendsBearerKeyJsonModeAndBothPromptsAndReturnsTheContent() throws Exception {
        List<Req> seen = new CopyOnWriteArrayList<>();
        HttpServer server = start(seen, r -> {
            try { return new Reply(200, completion("{\"ok\":true}")); } catch (IOException e) { throw new RuntimeException(e); }
        });
        try {
            // trailing slash on the base URL and a newline pasted after the key must both be tolerated
            String reply = client(server, "/", "sk-test\n", 0.1).completeJson("SYSTEM TEXT", "USER TEXT");
            assertEquals("{\"ok\":true}", reply);
            assertEquals(1, seen.size());
            Req r = seen.get(0);
            assertEquals("Bearer sk-test", r.auth());
            assertTrue(r.contentType().startsWith("application/json"));
            assertEquals("gpt-test", r.body().get("model").asText());
            assertEquals("json_object", r.body().path("response_format").path("type").asText());
            assertEquals(0.1, r.body().get("temperature").asDouble());
            assertEquals("system", r.body().path("messages").path(0).path("role").asText());
            assertEquals("SYSTEM TEXT", r.body().path("messages").path(0).path("content").asText());
            assertEquals("USER TEXT", r.body().path("messages").path(1).path("content").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test void temperatureIsOmittedWhenNotConfigured() throws Exception {
        List<Req> seen = new CopyOnWriteArrayList<>();
        HttpServer server = start(seen, r -> {
            try { return new Reply(200, completion("{}")); } catch (IOException e) { throw new RuntimeException(e); }
        });
        try {
            client(server, "", "k", null).completeJson("s", "u");
            assertFalse(seen.get(0).body().has("temperature"));
        } finally {
            server.stop(0);
        }
    }

    @Test void retriesOnceWithoutTemperatureWhenTheModelRejectsIt() throws Exception {
        List<Req> seen = new CopyOnWriteArrayList<>();
        HttpServer server = start(seen, r -> {
            if (r.body().has("temperature")) {
                return new Reply(400, "{\"error\":{\"message\":\"Unsupported value: 'temperature' does not support 0.1 with this model.\"}}");
            }
            try { return new Reply(200, completion("{\"ok\":1}")); } catch (IOException e) { throw new RuntimeException(e); }
        });
        try {
            assertEquals("{\"ok\":1}", client(server, "", "k", 0.1).completeJson("s", "u"));
            assertEquals(2, seen.size());
            assertFalse(seen.get(1).body().has("temperature"));
        } finally {
            server.stop(0);
        }
    }

    @Test void httpErrorsBecomeAiProcessingExceptionWithStatusAndNoKey() throws Exception {
        List<Req> seen = new ArrayList<>();
        HttpServer server = start(seen, r -> new Reply(429, "{\"error\":{\"message\":\"Rate limit reached\"}}"));
        try {
            AiProcessingException e = assertThrows(AiProcessingException.class,
                    () -> client(server, "", "sk-secret", 0.1).completeJson("s", "u"));
            assertTrue(e.getMessage().contains("HTTP 429"), e.getMessage());
            assertTrue(e.getMessage().contains("Rate limit reached"), e.getMessage());
            assertFalse(e.getMessage().contains("sk-secret"), e.getMessage());
            assertEquals(1, seen.size());
        } finally {
            server.stop(0);
        }
    }

    @Test void emptyContentIsAnError() throws Exception {
        HttpServer server = start(new ArrayList<>(), r -> new Reply(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}"));
        try {
            AiProcessingException e = assertThrows(AiProcessingException.class, () -> client(server, "", "k", null).completeJson("s", "u"));
            assertTrue(e.getMessage().contains("empty reply"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test void unreachableServerGivesAClearError() throws Exception {
        HttpServer server = start(new ArrayList<>(), r -> new Reply(200, "{}"));
        OpenAiLlmClient c = client(server, "", "k", null);
        server.stop(0);
        AiProcessingException e = assertThrows(AiProcessingException.class, () -> c.completeJson("s", "u"));
        assertTrue(e.getMessage().startsWith("OpenAI request failed"), e.getMessage());
    }
}
