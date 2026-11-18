package com.finance.analyzer.dto;

import java.util.List;

/** Reply to {@code POST /api/finance/analyze/async}: where to listen for progress. */
public record JobAccepted(String jobId, int totalChunks, List<String> chunkIds, String eventsUrl) {}
