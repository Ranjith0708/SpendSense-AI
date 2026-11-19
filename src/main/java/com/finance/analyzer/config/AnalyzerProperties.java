package com.finance.analyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds the {@code analyzer.*} block: how statements are chunked and how chunks are processed. */
@ConfigurationProperties(prefix = "analyzer")
public record AnalyzerProperties(
        @DefaultValue Chunking chunking,
        @DefaultValue Processing processing,
        @DefaultValue Logging logging) {

    /**
     * @param transactionsPerChunk target number of transaction records in one chunk
     * @param maxCharsPerChunk     safety cap so a chunk never produces an oversized prompt
     */
    public record Chunking(
            @DefaultValue("25") int transactionsPerChunk,
            @DefaultValue("8000") int maxCharsPerChunk) {}

    /**
     * @param logPayloads log each chunk's prompt input and the model's raw/parsed output at INFO (otherwise DEBUG).
     *                    The payloads contain statement data, so switch off where logs are shared.
     */
    public record Logging(@DefaultValue("false") boolean logPayloads) {}

    /**
     * @param concurrency       chunks processed in parallel (1 = strictly sequential)
     * @param maxAttempts       LLM attempts per call before the chunk is marked failed
     * @param minIntervalMs     minimum gap between the starts of two LLM calls (rate limit, 0 = off)
     * @param maxConcurrentJobs analysis jobs that may run at once
     */
    public record Processing(
            @DefaultValue("1") int concurrency,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("0") long minIntervalMs,
            @DefaultValue("2") int maxConcurrentJobs) {}
}
