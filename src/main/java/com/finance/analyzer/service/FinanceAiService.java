package com.finance.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.finance.analyzer.config.AnalyzerProperties;
import com.finance.analyzer.dto.AnalysisResponse;
import com.finance.analyzer.dto.AnalysisResponse.CategorySummary;
import com.finance.analyzer.dto.AnalysisResponse.Narrative;
import com.finance.analyzer.dto.AnalysisResponse.Transaction;
import com.finance.analyzer.dto.AnalysisResponse.TxnType;
import com.finance.analyzer.dto.ProgressEvent;
import com.finance.analyzer.dto.ProgressEvent.Stage;
import com.finance.analyzer.dto.StatementChunk;
import com.finance.analyzer.exception.AiProcessingException;
import com.finance.analyzer.model.ExpenseCategory;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the analysis: chunks go to the LLM one request each, partial outputs are accumulated, and then Java does
 * what must be exact (de-duplication, every total and percentage) before the LLM writes the coaching text.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FinanceAiService {

    /** Safe to show to users; the technical reason is always logged instead. */
    static final String GENERIC_FAILURE = "We couldn't analyze this statement right now. Please try again in a moment.";

    private final ChunkProcessor chunkProcessor;
    private final StructuredLlmService llm;
    private final AnalyzerProperties props;

    /** Runs the full pipeline. {@code listener} receives progress events and may be a no-op. */
    public AnalysisResponse analyze(String jobId, List<StatementChunk> chunks, Consumer<ProgressEvent> listener) {
        MDC.put("jobId", jobId);
        long start = System.nanoTime();
        try {
            int total = chunks.size();
            log.info("analysis_start jobId={} totalChunks={} concurrency={}", jobId, total, props.processing().concurrency());
            ProgressTracker tracker = new ProgressTracker(total);
            emit(listener, new ProgressEvent(jobId, Stage.CHUNKED, null, 0, 0, total, 0,
                    "Statement split into " + total + (total == 1 ? " chunk" : " chunks")));

            List<ChunkResult> results = processChunks(jobId, chunks, tracker, listener);

            // Merge partial outputs in chunk order so the final list keeps the statement's order.
            TransactionAccumulator acc = new TransactionAccumulator();
            List<String> warnings = new ArrayList<>();
            for (ChunkResult r : results) {
                if (r.success()) {
                    acc.add(r.chunk().id(), r.transactions());
                } else {
                    // Details are already logged (chunk_failed). The user only gets a plain-language note below.
                    log.warn("chunk_excluded chunkId={} reason={}", r.chunk().id(), r.error());
                }
            }
            List<Transaction> txns = acc.result();
            int failed = tracker.failed();
            if (failed > 0) {
                warnings.add("Some parts of your statement could not be analyzed, so a few transactions may be missing from the totals. "
                        + "Please try again, or upload a smaller statement.");
            }
            log.info("accumulation_complete jobId={} transactions={} failedChunks={}", jobId, txns.size(), failed);
            if (txns.isEmpty()) {
                if (failed == total) {
                    log.error("all_chunks_failed jobId={} firstReason={}", jobId, results.get(0).error());
                    throw new AiProcessingException(GENERIC_FAILURE, null);
                }
                throw new AiProcessingException("No transactions could be identified in this statement.", null);
            }

            emit(listener, new ProgressEvent(jobId, Stage.SUMMARIZING, null, tracker.completed(), failed, total, 100,
                    "Calculating totals and writing your summary"));
            AnalysisResponse response = buildResponse(txns, total, failed, warnings);
            log.info("analysis_complete jobId={} transactions={} totalExpense={} totalIncome={} durationMs={}", jobId,
                    txns.size(), response.totalExpense(), response.totalIncome(), (System.nanoTime() - start) / 1_000_000);
            return response;
        } finally {
            MDC.remove("jobId");
        }
    }

    private List<ChunkResult> processChunks(String jobId, List<StatementChunk> chunks, ProgressTracker tracker,
                                            Consumer<ProgressEvent> listener) {
        int threads = Math.max(1, Math.min(props.processing().concurrency(), chunks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ChunkResult>> futures = new ArrayList<>();
            for (StatementChunk chunk : chunks) {
                futures.add(pool.submit(() -> runChunk(jobId, chunk, tracker, listener)));
            }
            List<ChunkResult> results = new ArrayList<>();
            for (Future<ChunkResult> f : futures) results.add(f.get());
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProcessingException("Analysis was interrupted.", e);
        } catch (ExecutionException e) {
            throw new AiProcessingException("Chunk processing failed unexpectedly.", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private ChunkResult runChunk(String jobId, StatementChunk chunk, ProgressTracker tracker, Consumer<ProgressEvent> listener) {
        MDC.put("jobId", jobId);
        try {
            emit(listener, new ProgressEvent(jobId, Stage.CHUNK_STARTED, chunk.id(), tracker.completed(), tracker.failed(),
                    tracker.total(), tracker.percent(), "Processing " + chunk.id() + " of " + tracker.total()));
            ChunkResult result = chunkProcessor.process(chunk);
            // Update and publish under one lock so percentages reach the UI in increasing order.
            synchronized (tracker) {
                int pct = tracker.chunkFinished(result.success());
                log.info("progress jobId={} chunkId={} status={} completed={}/{} percent={}", jobId, chunk.id(),
                        result.success() ? "COMPLETED" : "FAILED", tracker.completed(), tracker.total(), pct);
                emit(listener, new ProgressEvent(jobId, result.success() ? Stage.CHUNK_COMPLETED : Stage.CHUNK_FAILED,
                        chunk.id(), tracker.completed(), tracker.failed(), tracker.total(), pct,
                        result.success() ? chunk.id() + " done" : chunk.id() + " failed"));
            }
            return result;
        } finally {
            MDC.remove("jobId");
        }
    }

    private static void emit(Consumer<ProgressEvent> listener, ProgressEvent event) {
        if (listener == null) return;
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            log.warn("progress_listener_failed stage={} reason={}", event.stage(), e.getMessage());
        }
    }

    private AnalysisResponse buildResponse(List<Transaction> txns, int totalChunks, int failedChunks, List<String> warnings) {
        List<Transaction> spend = txns.stream().filter(t -> t.type() == TxnType.DEBIT && !isInternal(t)).toList();
        double totalExpense = round(spend.stream().mapToDouble(Transaction::amount).sum());
        double totalIncome = round(txns.stream()
                .filter(t -> t.type() == TxnType.CREDIT && !t.selfTransfer()).mapToDouble(Transaction::amount).sum());

        List<CategorySummary> breakdown = spend.stream()
                .collect(Collectors.groupingBy(Transaction::category))
                .entrySet().stream()
                .map(e -> {
                    double sum = round(e.getValue().stream().mapToDouble(Transaction::amount).sum());
                    double pct = totalExpense == 0 ? 0 : Math.round(sum * 1000.0 / totalExpense) / 10.0;
                    return new CategorySummary(e.getKey(), e.getKey().getLabel(), e.getKey().getColor(),
                            e.getValue().size(), sum, pct);
                })
                .sorted(Comparator.comparingDouble(CategorySummary::totalAmount).reversed())
                .toList();

        int uncategorized = (int) spend.stream().filter(t -> t.category() == ExpenseCategory.OTHER_UNCATEGORIZED).count();
        List<String> allWarnings = new ArrayList<>(warnings);
        Narrative local = LocalInsights.build(totalExpense, totalIncome, breakdown, spend);
        Narrative n;
        try {
            n = LocalInsights.fillGaps(narrate(totalExpense, totalIncome, breakdown, recurring(spend)), local);
        } catch (AiProcessingException e) {
            log.error("narrative_failed reason={} (using locally computed insights)", e.getMessage(), e);
            allWarnings.add("We couldn't generate the AI summary, so the summary and tips below were generated automatically from your totals.");
            n = local;
        }
        return new AnalysisResponse(n.summary(), totalExpense, totalIncome, round(totalIncome - totalExpense),
                uncategorized, breakdown, txns, safe(n.insights()), safe(n.feedback()), safe(n.savingsTips()),
                totalChunks, failedChunks, allWarnings);
    }

    private Narrative narrate(double expense, double income, List<CategorySummary> breakdown, List<String> recurring) {
        StringBuilder sb = new StringBuilder("Aggregated statement data (excludes self-transfers):\n");
        sb.append("Total expenses: ").append(expense).append("\nTotal income: ").append(income).append("\nCategory spend:\n");
        breakdown.forEach(c -> sb.append("- ").append(c.displayLabel()).append(": ").append(c.totalAmount())
                .append(" (").append(c.percentage()).append("%, ").append(c.transactionCount()).append(" txns)\n"));
        sb.append("Recurring payees (3+ payments):\n");
        if (recurring.isEmpty()) sb.append("- none\n"); else recurring.forEach(r -> sb.append("- ").append(r).append('\n'));
        // Small models often answer in a slightly different shape (objects inside lists, a string instead of a list),
        // so read the reply as a tree and coerce each field instead of failing on a strict mapping.
        JsonNode root = llm.call(Prompts.ADVISE, sb.toString(), JsonNode.class, "narrative");
        return new Narrative(text(field(root, "summary")), list(field(root, "insights")),
                list(field(root, "feedback")), list(field(root, "savingsTips", "savings_tips", "tips")));
    }

    private static JsonNode field(JsonNode root, String... names) {
        for (String n : names) if (root.hasNonNull(n)) return root.get(n);
        return null;
    }

    /** Text from a string, or the joined text of an array or object. */
    private static String text(JsonNode n) {
        if (n == null || n.isNull()) return "";
        if (n.isValueNode()) return n.asText("").trim();
        List<String> parts = new ArrayList<>();
        n.forEach(c -> { String t = text(c); if (!t.isBlank()) parts.add(t); });
        return String.join(" ", parts);
    }

    private static List<String> list(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || n.isNull()) return out;
        if (n.isArray()) n.forEach(c -> { String t = text(c); if (!t.isBlank()) out.add(t); });
        else if (n.isObject()) n.forEach(c -> { String t = text(c); if (!t.isBlank()) out.add(t); });
        else for (String line : n.asText("").split("\\R")) {
            String t = line.replaceFirst("^[\\s\\-*\\u2022\\d.)]+", "").trim();
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    private List<String> recurring(List<Transaction> spend) {
        return spend.stream()
                .filter(t -> t.details() != null)
                .collect(Collectors.groupingBy(t -> t.details().toLowerCase().replaceAll("[^a-z]", "")))
                .values().stream()
                .filter(g -> g.size() >= 3)
                .sorted((a, b) -> b.size() - a.size())
                .limit(8)
                .map(g -> g.get(0).details() + ": " + g.size() + " payments totalling "
                        + round(g.stream().mapToDouble(Transaction::amount).sum()))
                .toList();
    }

    private boolean isInternal(Transaction t) {
        return t.selfTransfer() || t.category() == ExpenseCategory.INCOME_AND_TRANSFERS;
    }

    private static List<String> safe(List<String> l) { return l == null ? List.of() : l; }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
