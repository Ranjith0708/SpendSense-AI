package com.finance.analyzer.service;

import com.finance.analyzer.config.AnalyzerProperties;
import com.finance.analyzer.dto.AnalysisResponse;
import com.finance.analyzer.dto.JobAccepted;
import com.finance.analyzer.dto.ProgressEvent;
import com.finance.analyzer.dto.ProgressEvent.Stage;
import com.finance.analyzer.dto.StatementChunk;
import com.finance.analyzer.exception.AiProcessingException;
import com.finance.analyzer.exception.JobNotFoundException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Runs analyses in the background and streams their progress over Server-Sent Events.
 *
 * <p>SSE contract (all payloads are JSON):
 * <ul>
 *   <li>{@code progress}: a {@link ProgressEvent}, sent for every stage and chunk status change</li>
 *   <li>{@code result}: the final {@link AnalysisResponse}; the stream closes after it</li>
 *   <li>{@code failure}: {@code {"message": "..."}}; the stream closes after it</li>
 *   <li>{@code cancelled}: sent after {@code DELETE /jobs/{id}}; the stream closes after it</li>
 * </ul>
 * Every event is kept for the life of the job, so a client that connects late or reconnects gets the full history.
 */
@Service
@Slf4j
public class AnalysisJobService {

    private static final Duration RETENTION = Duration.ofMinutes(15);
    /** 0 = the connection never times out; a run on a slow local model can take well over half an hour. */
    private static final long SSE_TIMEOUT_MS = 0L;
    private static final long HEARTBEAT_SECONDS = 15;

    private final FinanceAiService ai;
    private final ExecutorService executor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public AnalysisJobService(FinanceAiService ai, AnalyzerProperties props) {
        this.ai = ai;
        AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(Math.max(1, props.processing().maxConcurrentJobs()), r -> {
            Thread t = new Thread(r, "analysis-job-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        // Comment lines keep proxies and browsers from closing an idle stream, and reveal clients that have gone away.
        heartbeat.scheduleWithFixedDelay(() -> {
                    jobs.values().forEach(Job::heartbeat);
                    evictExpired();
                },
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        heartbeat.shutdownNow();
    }

    /** Starts processing {@code chunks} in the background and returns immediately. */
    public JobAccepted submit(List<StatementChunk> chunks) {
        evictExpired();
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        Job job = new Job();
        jobs.put(jobId, job);
        log.info("job_submitted jobId={} totalChunks={}", jobId, chunks.size());
        job.future = executor.submit(() -> run(jobId, job, chunks));
        return new JobAccepted(jobId, chunks.size(), chunks.stream().map(StatementChunk::id).toList(),
                "/api/finance/jobs/" + jobId + "/events");
    }

    /** Stops a running job (interrupts in-flight LLM calls) and tells subscribers with a {@code cancelled} event. */
    public void cancel(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) throw new JobNotFoundException(jobId);
        log.info("job_cancel_requested jobId={}", jobId);
        job.cancel();
    }

    /** Attaches an SSE stream to the job, replaying everything that already happened. */
    public SseEmitter subscribe(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) throw new JobNotFoundException(jobId);
        log.info("job_subscribed jobId={}", jobId);
        return job.subscribe();
    }

    private void run(String jobId, Job job, List<StatementChunk> chunks) {
        MDC.put("jobId", jobId);
        try {
            AnalysisResponse result = ai.analyze(jobId, chunks, e -> job.publish("progress", e, false));
            job.publish("progress", new ProgressEvent(jobId, Stage.COMPLETED, null, chunks.size(), result.failedChunks(),
                    chunks.size(), 100, "Done"), false);
            job.publish("result", result, true);
        } catch (Exception e) {
            if (job.isTerminal()) {
                log.info("job_stopped jobId={}", jobId);
                return;
            }
            log.error("job_failed jobId={} reason={}", jobId, e.getMessage(), e);
            // FinanceAiService only throws AiProcessingException with user-safe text; anything else stays in the log.
            String shown = e instanceof AiProcessingException && e.getMessage() != null
                    ? e.getMessage() : FinanceAiService.GENERIC_FAILURE;
            job.publish("failure", Map.of("message", shown), true);
        } finally {
            MDC.remove("jobId");
        }
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(RETENTION);
        jobs.entrySet().removeIf(e -> e.getValue().finishedAt != null && e.getValue().finishedAt.isBefore(cutoff));
    }

    /** Event history plus the live subscribers of one job. */
    private static final class Job {
        private record Sent(String name, Object data) {}

        private final List<Sent> history = new ArrayList<>();
        private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private volatile Instant finishedAt;
        private boolean terminal;
        volatile Future<?> future;

        synchronized boolean isTerminal() { return terminal; }

        synchronized void heartbeat() {
            if (terminal) return;
            for (SseEmitter em : emitters) {
                try {
                    em.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | RuntimeException e) {
                    emitters.remove(em);
                }
            }
        }

        void cancel() {
            if (isTerminal()) return;
            // Publish first: once terminal, anything the interrupted job thread reports is ignored.
            publish("cancelled", Map.of("message", "Analysis cancelled"), true);
            Future<?> f = future;
            if (f != null) f.cancel(true);
        }

        synchronized void publish(String name, Object data, boolean last) {
            if (terminal) return;
            history.add(new Sent(name, data));
            for (SseEmitter em : emitters) {
                if (!send(em, new Sent(name, data))) emitters.remove(em);
                else if (last) em.complete();
            }
            if (last) {
                terminal = true;
                finishedAt = Instant.now();
                emitters.clear();
            }
        }

        synchronized SseEmitter subscribe() {
            SseEmitter em = new SseEmitter(SSE_TIMEOUT_MS);
            em.onCompletion(() -> emitters.remove(em));
            em.onTimeout(() -> emitters.remove(em));
            em.onError(t -> emitters.remove(em));
            for (Sent s : history) {
                if (!send(em, s)) return em;
            }
            if (terminal) em.complete(); else emitters.add(em);
            return em;
        }

        private static boolean send(SseEmitter em, Sent s) {
            try {
                em.send(SseEmitter.event().name(s.name()).data(s.data(), MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException e) {
                return false;
            }
        }
    }
}
