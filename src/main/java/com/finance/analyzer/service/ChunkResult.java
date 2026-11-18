package com.finance.analyzer.service;

import com.finance.analyzer.dto.AnalysisResponse.Transaction;
import com.finance.analyzer.dto.StatementChunk;
import java.util.List;

/** Outcome of processing one chunk. {@code error} is null on success. */
record ChunkResult(StatementChunk chunk, List<Transaction> transactions, String error) {

    static ChunkResult ok(StatementChunk chunk, List<Transaction> txns) { return new ChunkResult(chunk, txns, null); }

    static ChunkResult failed(StatementChunk chunk, String error) { return new ChunkResult(chunk, List.of(), error); }

    boolean success() { return error == null; }
}
