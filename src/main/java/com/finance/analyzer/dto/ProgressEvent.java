package com.finance.analyzer.dto;

/**
 * Progress snapshot pushed to the UI over SSE (event name {@code progress}).
 *
 * @param jobId           analysis job
 * @param stage           pipeline stage
 * @param chunkId         chunk this event is about, or null for job-level events
 * @param completedChunks chunks finished so far (successfully or not)
 * @param failedChunks    chunks that failed after all retries
 * @param totalChunks     N, fixed once the document has been chunked
 * @param percent         0..100, monotonically non-decreasing
 * @param message         short human-readable status
 */
public record ProgressEvent(String jobId, Stage stage, String chunkId, int completedChunks, int failedChunks,
                            int totalChunks, int percent, String message) {

    public enum Stage { CHUNKED, CHUNK_STARTED, CHUNK_COMPLETED, CHUNK_FAILED, SUMMARIZING, COMPLETED }
}
