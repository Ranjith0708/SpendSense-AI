package com.finance.analyzer.llm;

import java.util.Locale;

/** The supported LLM back ends, resolved from the {@code llm.provider} property. */
public enum LlmProvider {
    LOCAL, OPENAI;

    /** Accepts local/ollama/codellama and openai/dev/cloud (case-insensitive). */
    public static LlmProvider from(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "local", "ollama", "codellama" -> LOCAL;
            case "openai", "dev", "cloud" -> OPENAI;
            default -> throw new IllegalStateException(
                    "Unsupported llm.provider '" + value + "'. Use 'local' (Ollama/CodeLlama) or 'openai'.");
        };
    }
}
