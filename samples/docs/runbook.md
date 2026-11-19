# Runbook

How to build, run, configure, operate and troubleshoot SpendSense AI.

## 1. Prerequisites

| Requirement | Version | Check |
|---|---|---|
| JDK | 17 or newer | `java -version` |
| Maven | 3.9 or newer | `mvn -version` |
| OpenAI API key | with credit | https://platform.openai.com/api-keys |
| Docker (optional) | any recent | `docker --version` |

Internet access to `api.openai.com` (or your `OPENAI_BASE_URL`) is required at runtime.

## 2. Configure

The app reads everything from environment variables with defaults in `src/main/resources/application.yml`. Only the key is mandatory; the app **refuses to start** without it.

| Variable | Default | Purpose |
|---|---|---|
| `OPENAI_API_KEY` | none (required) | OpenAI credential |
| `OPENAI_MODEL` | `gpt-4o-mini` | Chat model. Try `gpt-4o` for harder statements |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Change only for a proxy or compatible gateway |
| `PORT` | `9090` | HTTP port |
| `LOG_PAYLOADS` | `false` | Logs prompts and raw replies. Contains personal data, keep off |

Set the key:

```bash
# macOS / Linux
export OPENAI_API_KEY="sk-..."
```
```powershell
# Windows PowerShell
$env:OPENAI_API_KEY="sk-..."
```
```bat
:: Windows cmd
set OPENAI_API_KEY=sk-...
```

Never commit the key. Keep it in your shell, a secrets manager, or your platform's secret settings.

## 3. Run locally

### Option A: Maven (development)

```bash
mvn spring-boot:run
```

### Option B: Jar

```bash
mvn clean package
java -jar target/finance-ai-analyzer-1.0.0.jar
```

### Option C: Docker

```bash
docker build -t spendsense-ai .
docker run --rm -p 9090:9090 -e OPENAI_API_KEY="$OPENAI_API_KEY" spendsense-ai
```

The image is multi-stage (Maven build, then `eclipse-temurin:17-jre`), runs as a non-root user (`uid 10001`), exposes `9090`, and uses `-XX:MaxRAMPercentage=75`.

### Verify it is up

```bash
curl http://localhost:9090/actuator/health      # {"status":"UP"}
```

Open **http://localhost:9090**, drop in `samples/PhonePe_Test_Statement_Short.pdf` (or your own statement) and select **Analyze statement**.

## 4. Use the app

1. Choose or drag a PDF, CSV, XLSX or XLS (max 20 MB).
2. Enter the PDF password if it is locked.
3. Select **Analyze statement** and watch the progress bar. **Cancel** stops the job.
4. Review the tabs: Overview, Transactions, Insights.
5. **Download report** produces a PDF in your browser.

Use the digital statement from the app or bank. Scanned PDFs are rejected.

## 5. Run the tests

```bash
mvn test
```

No API key is needed and no OpenAI calls are made. See [rest-api-tests.md](rest-api-tests.md) for endpoint checks against a running instance.

## 6. Tuning

Edit `application.yml` or override with Spring's environment mapping (for example `ANALYZER_PROCESSING_CONCURRENCY=1`).

| Property | Default | When to change |
|---|---|---|
| `analyzer.chunking.transactions-per-chunk` | 25 | Lower if calls time out; raise to reduce request count |
| `analyzer.chunking.max-chars-per-chunk` | 8000 | Hard prompt-size cap |
| `analyzer.processing.concurrency` | 2 | Lower to 1 on HTTP 429; raise carefully for speed |
| `analyzer.processing.max-attempts` | 3 | Retries per chunk |
| `analyzer.processing.min-interval-ms` | 0 | Add a gap between OpenAI calls to stay under rate limits |
| `analyzer.processing.max-concurrent-jobs` | 2 | Simultaneous analyses |
| `llm.timeout-seconds` | 120 | Raise for slow models or large chunks |
| `llm.openai.temperature` | 0.1 | Dropped automatically if the model rejects it |
| `spring.servlet.multipart.max-file-size` | 20MB | Upload limit (also change `max-request-size`) |

Worst-case concurrent OpenAI calls = `max-concurrent-jobs × concurrency` (default 4).

## 7. Deploy

The Dockerfile comment notes the platform injects `PORT`, which the app honours (`server.port: ${PORT:9090}`).

Checklist for any host:

- Set `OPENAI_API_KEY` as a secret, not in the image.
- Expose the container port equal to `PORT` (default 9090).
- Use `/actuator/health` for liveness and readiness probes.
- If behind a reverse proxy, disable response buffering for `/api/finance/jobs/*/events` (the app sends `X-Accel-Buffering: no`) and allow idle connections beyond 15 seconds (heartbeats are sent every 15 s).
- Allow request bodies up to 20 MB.
- Run a **single instance**. Job state is in memory, so a second instance cannot serve another instance's job (404 on the events URL). Use sticky routing if you must scale out.
- Add authentication at the gateway; the app has none.

Graceful shutdown is enabled (`server.shutdown: graceful`).

## 8. Logging and monitoring

Log line format: `HH:mm:ss.SSS LEVEL [job=<id>] [<chunk>] logger - message`.

Useful event keys to search for:

| Key | Meaning |
|---|---|
| `api_invoked` | Request received |
| `parse_start` / `parse_complete` / `parse_failed` | File parsing |
| `job_submitted` / `job_failed` / `job_stopped` | Job lifecycle |
| `analysis_start` / `analysis_complete` | Pipeline start/end with `durationMs` |
| `chunk_start` / `chunk_complete` / `chunk_failed` | Per-chunk results |
| `llm_call_ok` / `llm_call_failed` | OpenAI call outcome and latency |
| `chunk_accumulated` | Accepted, duplicate, invalid and corrected counts |
| `narrative_failed` | AI summary failed; local fallback used |
| `all_chunks_failed` | Whole analysis failed |

Statement contents are logged only when `LOG_PAYLOADS=true` (INFO) or when DEBUG is enabled for `com.finance`. Keep both off wherever logs are shared.

## 9. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Startup error "OpenAI API key is missing" | `OPENAI_API_KEY` not set in that shell | Export it in the same terminal, then rerun |
| `OpenAI returned HTTP 401` in logs | Wrong or revoked key | Create a new key |
| `OpenAI returned HTTP 429` | Rate limit or no credit | Check billing; set `concurrency=1`; set `min-interval-ms` |
| "no reply within 120s" | Slow model or large chunk | Raise `llm.timeout-seconds` or lower `transactions-per-chunk` |
| 422 "No readable text found" | Scanned PDF | Download the digital statement |
| 422 "password protected" | Locked PDF | Enter the password |
| "Heads up" banner / `failedChunks > 0` | Some chunks failed after retries | Retry, upload a smaller statement, or try `gpt-4o` |
| Banner "couldn't generate the AI summary" | Narrative call failed | Totals are still exact; retry for AI text |
| Wrong categories | Model guess on ambiguous payee | Check `OTHER_UNCATEGORIZED`; add a keyword to `CategoryRules` |
| Events URL returns 404 | Job older than 15 minutes, app restarted, or different instance | Start a new analysis |
| Progress stalls behind a proxy | Proxy buffers SSE | Disable buffering for the events path |
| Port already in use | Another process on 9090 | Set `PORT` to a free port |
| `mvn` fails on Java version | JDK older than 17 | Install JDK 17+ |

## 10. Routine operations

| Task | How |
|---|---|
| Rotate the OpenAI key | Update the secret, restart the app |
| Change model | Set `OPENAI_MODEL`, restart |
| Add a category rule | Edit `CategoryRules.RULES`, add a test in `CategoryRulesTest`, rebuild |
| Change the AI instructions | Edit `Prompts.java` (see [ai-usage.md](ai-usage.md)), run `mvn test`, rebuild |
| Add another AI provider | Implement `LlmClient`, register it as a Spring component, remove or qualify `OpenAiLlmClient` |
| Clear in-memory jobs | Restart the app |

## 11. Data handling

- Uploaded files are held in memory only for the duration of the request; nothing is written to disk or a database.
- Results stay in memory for 15 minutes after completion, then are dropped.
- Statement text is sent to OpenAI. Do not analyse statements you are not comfortable sharing with it.
