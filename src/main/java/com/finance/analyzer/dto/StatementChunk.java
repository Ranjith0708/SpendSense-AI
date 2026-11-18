package com.finance.analyzer.dto;

/**
 * One slice of the statement sent to the LLM in a single request.
 *
 * @param id          sequential identifier, {@code chunk_1}, {@code chunk_2}, ...
 * @param index       1-based position in the statement
 * @param recordCount number of transaction records in {@link #text()}
 * @param text        cleaned statement text for this chunk
 */
public record StatementChunk(String id, int index, int recordCount, String text) {}
