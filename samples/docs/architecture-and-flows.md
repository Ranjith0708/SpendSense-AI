# Architecture and Flows

## 1. System context

```mermaid
flowchart LR
    U[User browser<br/>HTML / CSS / JS] -->|multipart upload| API[Spring Boot app<br/>port 9090]
    API -->|chat/completions<br/>JSON mode| OAI[(OpenAI API)]
    API -->|SSE progress + result| U
```

A single Spring Boot process serves both the static dashboard and the REST API. There is no database and no message broker. Jobs live in memory.

## 2. Package layout

```
com.finance.analyzer
├── FinanceAnalyzerApplication      Spring Boot entry point
├── controller/
│   └── FinanceController           REST endpoints under /api/finance
├── service/
│   ├── StatementParserService      File to clean text to chunks
│   ├── AnalysisJobService          Background jobs, SSE streaming, cancel, retention
│   ├── FinanceAiService            Pipeline orchestrator, totals, narrative
│   ├── ChunkProcessor              One chunk to one LLM call to transactions
│   ├── StructuredLlmService        Retries, rate limit, JSON extraction and mapping
│   ├── TransactionAccumulator      Merge, validate, de-duplicate
│   ├── CategoryRules               Deterministic keyword corrections
│   ├── LocalInsights               Non-AI fallback summary and tips
│   ├── Prompts                     The two system prompts
│   ├── ProgressTracker             Percent complete maths
│   └── RateLimiter                 Minimum gap between LLM calls
├── llm/
│   ├── LlmClient                   Provider-neutral interface
│   ├── OpenAiLlmClient             OpenAI implementation (JDK HttpClient)
│   └── LlmHttp                     HTTP helpers
├── config/                         LlmProperties, AnalyzerProperties
├── dto/                            AnalysisResponse, StatementChunk, ProgressEvent, JobAccepted
├── model/                          ExpenseCategory (14 values, labels, colours)
└── exception/                      Typed exceptions + GlobalExceptionHandler
```

Static UI lives in `src/main/resources/static` (`index.html`, `css/styles.css`, `js/app.js`, `js/report.js`, vendored jsPDF).

## 3. Layered view

```mermaid
flowchart TB
    subgraph Web
        C[FinanceController]
        E[GlobalExceptionHandler]
    end
    subgraph Orchestration
        J[AnalysisJobService]
        F[FinanceAiService]
    end
    subgraph Processing
        P[StatementParserService]
        CP[ChunkProcessor]
        S[StructuredLlmService]
        A[TransactionAccumulator]
        R[CategoryRules]
        L[LocalInsights]
    end
    subgraph Integration
        LC[LlmClient interface]
        O[OpenAiLlmClient]
    end
    C --> P
    C --> J
    C --> F
    J --> F
    F --> CP --> S --> LC --> O
    F --> A --> R
    F --> S
    F --> L
```

`LlmClient` is the seam for swapping providers: implement `model()` and `completeJson(...)` and the rest of the app is unchanged.

## 4. Main flow: async analysis (what the UI uses)

```mermaid
sequenceDiagram
    participant B as Browser
    participant C as FinanceController
    participant P as StatementParserService
    participant J as AnalysisJobService
    participant F as FinanceAiService
    participant O as OpenAI

    B->>C: POST /api/finance/analyze/async (file, password)
    C->>P: parse(file, password)
    P-->>C: List of StatementChunk
    C->>J: submit(chunks)
    J-->>C: JobAccepted(jobId, eventsUrl)
    C-->>B: 202 Accepted
    B->>C: GET /jobs/{id}/events (EventSource)
    C->>J: subscribe(jobId)
    J-->>B: replay history, then live events
    J->>F: analyze(jobId, chunks, listener)
    par each chunk, up to concurrency
        F->>O: categorise chunk (system prompt CATEGORIZE)
        O-->>F: JSON transactions
        F-->>B: progress CHUNK_STARTED / CHUNK_COMPLETED
    end
    F->>F: merge, rules, de-duplicate, totals
    F-->>B: progress SUMMARIZING
    F->>O: narrative from aggregates (system prompt ADVISE)
    O-->>F: JSON summary / insights / feedback / tips
    F-->>J: AnalysisResponse
    J-->>B: progress COMPLETED, then result
```

Parsing happens **before** the job is created, because the uploaded file is discarded when the request ends. Parse errors therefore return `422` immediately instead of arriving later over SSE.

## 5. Parsing and chunking

```mermaid
flowchart TD
    A[Uploaded file] --> B{Extension}
    B -->|.pdf| P1[PDFBox text stripper<br/>sorted by position]
    B -->|.csv| P2[Commons CSV<br/>Header=value pairs per row]
    B -->|.xlsx / .xls| P3[Apache POI<br/>cells joined with pipe]
    B -->|other| X[422 Unsupported file type]
    P1 --> C[Clean lines<br/>collapse spaces, drop page numbers,<br/>footers, disclaimers]
    P2 --> C
    P3 --> C
    C --> D{Any line starts with a date?}
    D -->|yes| E[Merge continuation lines<br/>into the preceding record]
    D -->|no| F[Each line is one record]
    E --> G[Pack records into chunks]
    F --> G
    G --> H[chunk_1, chunk_2, ...]
```

A chunk closes when it reaches `transactions-per-chunk` records (default 25) or would exceed `max-chars-per-chunk` (default 8000), whichever comes first. A chunk never splits a transaction.

## 6. Chunk processing and retry

```mermaid
flowchart TD
    S[Chunk text] --> RL[RateLimiter.acquire]
    RL --> CALL[OpenAI chat/completions<br/>response_format json_object]
    CALL --> OK{HTTP 200 and parseable?}
    OK -->|yes| DONE[TransactionBatch]
    OK -->|no| RT{Attempts left?}
    RT -->|yes| WAIT[Pause 1s x attempt] --> RL
    RT -->|no| FAIL[Chunk marked FAILED<br/>others continue]
```

- Default 3 attempts per chunk (`analyzer.processing.max-attempts`).
- If OpenAI returns HTTP 400 mentioning `temperature`, the client resends once without it (for models such as o-series or gpt-5 that only accept the default).
- A failed chunk never fails the job. It is counted in `failedChunks` and a warning is added. Only if **all** chunks fail does the job fail.

## 7. Post-processing and totals

```mermaid
flowchart LR
    A[Chunk results in order] --> B[Drop invalid rows<br/>amount <= 0, null type]
    B --> C[CategoryRules.apply<br/>keyword corrections]
    C --> D[De-duplicate on 12-digit UTR]
    D --> E[Exclude self-transfers<br/>and INCOME_AND_TRANSFERS]
    E --> F[Totals, category %,<br/>uncategorized count]
    F --> G[Recurring payees<br/>3 or more payments]
    G --> H[Narrative call]
    H -->|failure or blank fields| I[LocalInsights fallback]
```

Rules worth knowing:

- Credits are always forced to `INCOME_AND_TRANSFERS`. Money in is never a spending category.
- **Spend** = DEBIT transactions that are not self-transfers and not `INCOME_AND_TRANSFERS`.
- **Income** = CREDIT transactions that are not self-transfers.
- Rows without a 12-digit UTR are always kept, because identical payments can be genuinely separate.
- Percentages use one decimal place; amounts round to two.

## 8. Job lifecycle and SSE

```mermaid
stateDiagram-v2
    [*] --> Running: submit()
    Running --> Completed: result published
    Running --> Failed: failure published
    Running --> Cancelled: DELETE /jobs/{id}
    Completed --> [*]: evicted after 15 min
    Failed --> [*]: evicted after 15 min
    Cancelled --> [*]: evicted after 15 min
```

- Every event is stored for the life of the job. A client that connects late, or reconnects after a network blip, gets the full history replayed.
- A heartbeat sends a `keep-alive` comment every 15 seconds so proxies do not close idle streams.
- The SSE connection has no timeout (`0`), because large statements can take minutes.
- At most `max-concurrent-jobs` (default 2) analyses run at once; others queue.
- Cancelling publishes `cancelled` first, then interrupts in-flight work, so late events from the interrupted thread are ignored.

## 9. Progress percentage

`ProgressTracker` adds `floor(100 / N)` per finished chunk, caps at 99 until the last chunk, then reports 100. With more than 100 chunks it uses proportional maths. The percentage never goes backwards, because updates and publishing share one lock.

## 10. Error handling

| Situation | Exception | HTTP |
|---|---|---|
| Empty file, wrong type, scan, wrong password, corrupt file | `StatementParseException` | 422 |
| LLM failed after retries, no transactions found | `AiProcessingException` | 502 |
| Unknown or expired job id | `JobNotFoundException` | 404 |
| File over 20 MB | `MaxUploadSizeExceededException` | 413 |
| Anything unexpected | `Exception` | 500 |

All errors use the body `{"message": "..."}`. Technical detail goes to logs only; users see safe text.

## 11. Concurrency model

| Pool | Size | Purpose |
|---|---|---|
| Job executor | `max-concurrent-jobs` (2) | Runs whole analyses |
| Chunk pool (per job) | `concurrency` (2), capped by chunk count | Parallel LLM calls |
| SSE heartbeat | 1 daemon thread | Keep-alives and eviction |

Total in-flight OpenAI calls can reach `max-concurrent-jobs × concurrency`. Lower `concurrency` if you hit HTTP 429.

## 12. Frontend flow

1. On load, fetch `/api/finance/categories` for labels and colours.
2. On **Analyze**, POST to `/analyze/async`, read `jobId` and `eventsUrl`.
3. Open an `EventSource`; update the progress bar on `progress`; render the dashboard on `result`.
4. **Cancel** calls `DELETE /jobs/{id}`.
5. **Download report** builds the PDF in the browser with jsPDF; nothing is sent back to the server.

## 13. Design decisions

| Decision | Reason |
|---|---|
| AI extracts, Java calculates | Models make arithmetic mistakes; totals must be exact |
| Chunking | Keeps prompts small and fast, isolates failures, allows parallelism and progress |
| JSON mode plus tolerant parsing | Small models vary their shape; extra prose and code fences are tolerated |
| Keyword rules after the model | Cheap, deterministic fix for obvious merchants |
| Local insights fallback | The Insights tab is never empty, even when the narrative call fails |
| In-memory jobs | Simple, no infrastructure; trade-off is no persistence or horizontal scaling |
| Provider interface | OpenAI today, another provider by adding one class |
