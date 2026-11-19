package com.finance.analyzer.llm;

/** Small helpers shared by the LLM clients. */
final class LlmHttp {

    private LlmHttp() {}

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
