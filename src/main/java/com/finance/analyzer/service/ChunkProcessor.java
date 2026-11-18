package com.finance.analyzer.service;

import com.finance.analyzer.config.AnalyzerProperties;
import com.finance.analyzer.dto.AnalysisResponse.TransactionBatch;
import com.finance.analyzer.dto.StatementChunk;
import com.finance.analyzer.exception.AiProcessingException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** Sends one chunk to the LLM and returns its transactions (never throws for model failures). */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChunkProcessor {

    private final StructuredLlmService llm;
    private final AnalyzerProperties props;

    ChunkResult process(StatementChunk chunk) {
        MDC.put("chunkId", chunk.id());
        long start = System.nanoTime();
        try {
            log.info("chunk_start chunkId={} index={} records={} chars={}",
                    chunk.id(), chunk.index(), chunk.recordCount(), chunk.text().length());
            TransactionBatch batch = llm.call(Prompts.CATEGORIZE,
                    "Extract and categorise all transactions from this statement text:\n\n" + chunk.text(),
                    TransactionBatch.class, chunk.id());
            List<com.finance.analyzer.dto.AnalysisResponse.Transaction> txns =
                    batch.transactions() == null ? List.of() : batch.transactions();
            if (props.logging().logPayloads()) {
                log.info("chunk_output chunkId={} parsedTransactions={}", chunk.id(), txns);
            } else {
                log.debug("chunk_output chunkId={} parsedTransactions={}", chunk.id(), txns);
            }
            log.info("chunk_complete chunkId={} extracted={} durationMs={}",
                    chunk.id(), txns.size(), (System.nanoTime() - start) / 1_000_000);
            return ChunkResult.ok(chunk, txns);
        } catch (AiProcessingException e) {
            log.error("chunk_failed chunkId={} durationMs={} reason={}",
                    chunk.id(), (System.nanoTime() - start) / 1_000_000, e.getMessage(), e);
            return ChunkResult.failed(chunk, e.getMessage());
        } catch (RuntimeException e) {
            log.error("chunk_failed chunkId={} unexpected error", chunk.id(), e);
            return ChunkResult.failed(chunk, "Unexpected error: " + e.getMessage());
        } finally {
            MDC.remove("chunkId");
        }
    }
}
