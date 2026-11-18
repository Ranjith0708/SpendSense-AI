package com.finance.analyzer.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Shared construction of {@link RestClient} instances with sane timeouts. */
final class LlmHttp {

    private LlmHttp() {}

    static RestClient restClient(String baseUrl, int readTimeoutSeconds) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** True if any exception in the cause chain is a timeout. */
    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().contains("Timeout")) return true;
        }
        return false;
    }

    /** Never-null description of the root cause: {@code ClassName: message}. */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
