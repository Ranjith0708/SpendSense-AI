# SpendSense AI (Finance AI Analyzer)

Upload a bank/UPI statement (PDF, CSV, XLSX) and get categorised spending plus coaching tips.

## Run

```bash
# Local CodeLlama via Ollama (default)
ollama pull codellama
mvn spring-boot:run

# OpenAI
export OPENAI_API_KEY=sk-...
LLM_PROVIDER=openai mvn spring-boot:run      # or: --spring.profiles.active=openai
```

Open http://localhost:9090.

## Sample data

`samples/PhonePe_Test_Statement_Short.pdf` is a 4-page PhonePe-style statement with 32 transactions (person and merchant
payments, credits, recharges, a self transfer, a masked account, a wrapped amount). About 4 chunks at the default size.

## UI

Light theme with three tabs: **Overview** (totals, chart, category breakdown, top payees, largest payments),
**Transactions** (search and filters; selecting a category in the breakdown opens it here) and **Insights**.
While analysing, a pop-up shows the progress bar and a cancel button; the upload section is locked.

## Provider configuration (`application.yml`)

| Property | Env var | Default |
|---|---|---|
| `llm.provider` (`local` / `openai`; `dev`, `cloud` = `openai`) | `LLM_PROVIDER` | `local` |
| `llm.local.base-url` | `OLLAMA_BASE_URL` | `http://localhost:11434` |
| `llm.local.model` | `LOCAL_LLM_MODEL` | `codellama` |
| `llm.local.api-key` (optional) | `OLLAMA_API_KEY` | empty |
| `llm.local.context-length` (Ollama `num_ctx`) | | `8192` |
| `llm.local.max-output-tokens` (Ollama `num_predict`) | | `4096` |
| `llm.openai.base-url` | `OPENAI_BASE_URL` | `https://api.openai.com/v1` |
| `llm.openai.model` | `OPENAI_MODEL` | `gpt-4o-mini` |
| `llm.openai.api-key` (required) | `OPENAI_API_KEY` | empty |

Misconfiguration (unknown provider, missing OpenAI key) fails at startup.

The local client streams Ollama's reply (like `ollama run`) and sets the context window and an output cap;
`llm.timeout-seconds` is a cap on one whole call. The old `spring.ai.ollama.*` properties are no longer used.

## Chunking (`analyzer.*`)

`transactions-per-chunk` (10), `max-chars-per-chunk` (8000), `concurrency` (1), `max-attempts` (2),
`min-interval-ms` (0), `max-concurrent-jobs` (2), `logging.log-payloads` (true). `llm.timeout-seconds` defaults to 600.

## API

| Endpoint | Purpose |
|---|---|
| `POST /api/finance/analyze/async` (multipart `file`, optional `password`) | Parses and chunks the file, starts a background job. `202` -> `{jobId, totalChunks, chunkIds, eventsUrl}` |
| `GET /api/finance/jobs/{jobId}/events` | SSE stream. `progress` (ProgressEvent), then `result` (AnalysisResponse) or `failure` (`{message}`). History is replayed on connect. |
| `DELETE /api/finance/jobs/{jobId}` | Cancels a running job (stream ends with a `cancelled` event) |
| `POST /api/finance/analyze` | Blocking variant, returns the AnalysisResponse directly |

`progress` payload: `{jobId, stage, chunkId, completedChunks, failedChunks, totalChunks, percent, message}`,
stages: `CHUNKED, CHUNK_STARTED, CHUNK_COMPLETED, CHUNK_FAILED, SUMMARIZING, COMPLETED`.

## Logging

Pattern includes `job=<id>` and `chunk_<n>` (MDC). INFO = milestones (`api_invoked`, `chunk_start`, `chunk_complete`,
`chunk_accumulated`, `progress`, `analysis_complete`), chunk prompt input, raw LLM output and parsed output are logged at INFO while `analyzer.logging.log-payloads=true` (otherwise DEBUG; they contain statement data),
ERROR = failures. Enable with `logging.level.com.finance=DEBUG`.
