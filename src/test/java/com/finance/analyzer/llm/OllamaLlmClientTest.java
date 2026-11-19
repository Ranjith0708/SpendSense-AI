package com.finance.analyzer.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.finance.analyzer.config.LlmProperties;
import com.finance.analyzer.exception.AiProcessingException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Talks to a throw-away local HTTP server that imitates Ollama's streamed /api/chat reply. */
class OllamaLlmClientTest {

    private interface Script { void play(OutputStream out, CountDownLatch release) throws Exception; }

    private static String line(String content, boolean done) {
        return "{\"message\":{\"role\":\"assistant\",\"content\":" + quote(content) + "},\"done\":" + done
                + (done ? ",\"done_reason\":\"stop\",\"eval_count\":5,\"prompt_eval_count\":7" : "") + "}\n";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static HttpServer start(Script script, CountDownLatch release) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                script.play(out, release);
            } catch (Exception ignored) {
                // the client closed the stream: expected when it stops reading early
            } finally {
                ex.close();
            }
        });
        server.start();
        return server;
    }

    private static OllamaLlmClient client(HttpServer server, int timeoutSeconds) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        LlmProperties lp = new LlmProperties("local", timeoutSeconds,
                new LlmProperties.Endpoint(url, "", "tiny", 0.1, 2048, 256),
                new LlmProperties.Endpoint("http://unused", "", "m", null, null, null));
        return new OllamaLlmClient(lp);
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Test void normalStreamedReplyIsJoined() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = start((out, r) -> {
            write(out, line("{\"a\":", false));
            write(out, line("1}", false));
            write(out, line("", true));
        }, release);
        try {
            assertEquals("{\"a\":1}", client(server, 20).completeJson("sys", "user"));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    /** Regression test: a model that finishes the JSON but never sends "done" must not run until the timeout. */
    @Test void returnsAsSoonAsTheJsonObjectIsCompleteEvenIfTheModelKeepsGoing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = start((out, r) -> {
            write(out, line("{\"summary\":\"fine\",", false));
            write(out, line("\"insights\":[\"x\"]}", false));
            r.await(30, TimeUnit.SECONDS);          // the "runaway" model: no further tokens, no done flag
        }, release);
        try {
            long start = System.nanoTime();
            String reply = client(server, 20).completeJson("sys", "user");
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            assertEquals("{\"summary\":\"fine\",\"insights\":[\"x\"]}", reply);
            assertTrue(seconds < 10, "took " + seconds + "s");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test void perCallTimeoutIsHonouredAndReportedInTheMessage() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = start((out, r) -> {
            write(out, line("{\"partial\":", false));
            r.await(30, TimeUnit.SECONDS);          // stalls mid-object
        }, release);
        try {
            long start = System.nanoTime();
            AiProcessingException e = assertThrows(AiProcessingException.class,
                    () -> client(server, 600).completeJson("sys", "user", 100, 2));
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            assertTrue(seconds < 15, "took " + seconds + "s");
            assertTrue(e.getMessage().contains("within 2s"), e.getMessage());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test void ollamaErrorLineBecomesAnAiProcessingException() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = start((out, r) -> write(out, "{\"error\":\"model not found\"}\n"), release);
        try {
            AiProcessingException e = assertThrows(AiProcessingException.class, () -> client(server, 20).completeJson("s", "u"));
            assertTrue(e.getMessage().contains("model not found"), e.getMessage());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test void jsonObjectEndIgnoresBracesInsideStringsAndEscapes() {
        OllamaLlmClient.JsonObjectEnd end = new OllamaLlmClient.JsonObjectEnd();
        assertFalse(end.feed("{\"a\":\"}\\\"}\",\"b\":[{\"c\":1}]"));
        assertTrue(end.feed("}"));
    }

    @Test void jsonObjectEndSkipsLeadingProse() {
        assertTrue(new OllamaLlmClient.JsonObjectEnd().feed("Sure: {\"k\":{}}"));
        assertFalse(new OllamaLlmClient.JsonObjectEnd().feed("no json yet"));
    }
}
