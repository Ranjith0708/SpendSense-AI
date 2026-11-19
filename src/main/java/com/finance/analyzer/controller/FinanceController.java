package com.finance.analyzer.controller;

import com.finance.analyzer.dto.AnalysisResponse;
import com.finance.analyzer.dto.JobAccepted;
import com.finance.analyzer.dto.StatementChunk;
import com.finance.analyzer.model.ExpenseCategory;
import com.finance.analyzer.service.AnalysisJobService;
import com.finance.analyzer.service.FinanceAiService;
import com.finance.analyzer.service.StatementParserService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
@Slf4j
public class FinanceController {

    private final StatementParserService parser;
    private final FinanceAiService ai;
    private final AnalysisJobService jobs;

    /** Strips line breaks and other control characters from user-supplied values before they are logged. */
    static String safe(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}", "_");
    }

    /** Blocking analysis: returns the final result when every chunk is done. Kept for API compatibility. */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalysisResponse> analyze(@RequestParam("file") MultipartFile file,
                                                    @RequestParam(value = "password", required = false) String password) {
        log.info("api_invoked POST /api/finance/analyze file={} sizeBytes={}", safe(file.getOriginalFilename()), file.getSize());
        List<StatementChunk> chunks = parser.parse(file, password);
        return ResponseEntity.ok(ai.analyze("sync-" + UUID.randomUUID().toString().substring(0, 8), chunks, null));
    }

    /** Starts a background analysis and returns the job to follow; progress arrives on {@link #events}. */
    @PostMapping(value = "/analyze/async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobAccepted> analyzeAsync(@RequestParam("file") MultipartFile file,
                                                    @RequestParam(value = "password", required = false) String password) {
        log.info("api_invoked POST /api/finance/analyze/async file={} sizeBytes={}", safe(file.getOriginalFilename()), file.getSize());
        // Parse before returning: the upload is discarded once this request ends, and parse errors map to 422 right away.
        List<StatementChunk> chunks = parser.parse(file, password);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobs.submit(chunks));
    }

    /** Server-Sent Events stream: {@code progress}, then {@code result} or {@code failure}. */
    @GetMapping(value = "/jobs/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String jobId) {
        log.info("api_invoked GET /api/finance/jobs/{}/events", safe(jobId));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(jobs.subscribe(jobId));
    }

    /** Cancels a running analysis. */
    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Void> cancel(@PathVariable String jobId) {
        log.info("api_invoked DELETE /api/finance/jobs/{}", safe(jobId));
        jobs.cancel(jobId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/categories")
    public List<Map<String, String>> categories() {
        return Arrays.stream(ExpenseCategory.values())
                .map(c -> Map.of("name", c.name(), "label", c.getLabel(), "color", c.getColor()))
                .toList();
    }
}
