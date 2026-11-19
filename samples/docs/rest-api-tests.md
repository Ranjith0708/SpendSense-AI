# REST API Tests

Manual and scripted checks for every endpoint. Start the app first (see [runbook.md](runbook.md)); the examples assume `http://localhost:9090` and run from the repository root.

> Tests that upload a statement call OpenAI and cost a small amount. The automated JUnit suite (`mvn test`) uses fake model replies and needs no key.

## 0. Setup

```bash
export BASE=http://localhost:9090
export FILE=samples/PhonePe_Test_Statement_Short.pdf
```

Create the CSV used by several tests:

```bash
cat > /tmp/statement.csv <<'CSV'
Date,Details,UTR,Debit,Credit
2026-10-01,SWIGGY,412345678901,450,
2026-10-02,ZEPTO,412345678902,820,
2026-10-03,BMRCL,412345678903,60,
2026-10-04,BMRCL,412345678904,60,
2026-10-05,BMRCL,412345678905,60,
2026-10-06,NETFLIX,412345678906,649,
2026-10-07,SALARY CREDIT,412345678907,,85000
2026-10-08,RAMESH KUMAR,412345678908,500,
2026-10-09,Self transfer to savings XXXX1234,412345678909,10000,
CSV
```

Expected for this CSV (rules are deterministic, the narrative text is not):

- `totalExpense` = **2599.0** (450 + 820 + 60×3 + 649 + 500; the 10,000 self-transfer is excluded)
- `totalIncome` = **85000.0**
- `netFlow` = **82401.0**
- Self-transfer row has `selfTransfer: true` and is excluded from both totals.
- `RAMESH KUMAR` is typically `OTHER_UNCATEGORIZED` (`uncategorizedCount` ≥ 1).
- BMRCL ×3 appears as a recurring payee in insights.

## 1. Test matrix

| # | Endpoint | Scenario | Expected |
|---|---|---|---|
| T1 | GET `/actuator/health` | App up | 200, `{"status":"UP"}` |
| T2 | GET `/api/finance/categories` | List | 200, 14 items |
| T3 | POST `/analyze` | Valid PDF | 200, `AnalysisResponse` |
| T4 | POST `/analyze` | Valid CSV | 200, totals as above |
| T5 | POST `/analyze` | `.txt` file | 422 unsupported type |
| T6 | POST `/analyze` | Empty file | 422 non-empty |
| T7 | POST `/analyze` | No `file` part | 4xx/5xx error body |
| T8 | POST `/analyze` | File over 20 MB | 413 |
| T9 | POST `/analyze` | Locked PDF, no password | 422 password protected |
| T10 | POST `/analyze/async` | Valid file | 202 with `jobId` |
| T11 | GET `/jobs/{id}/events` | Follow job | `progress` then `result` |
| T12 | GET `/jobs/{id}/events` | Reconnect after finish | Full replay |
| T13 | GET `/jobs/{id}/events` | Unknown id | 404 |
| T14 | DELETE `/jobs/{id}` | Running job | 204, `cancelled` event |
| T15 | DELETE `/jobs/{id}` | Unknown id | 404 |
| T16 | Any | Bad API key | `failure` / 502 |

## 2. Test cases

### T1 — Health

```bash
curl -s -o /dev/null -w "%{http_code}\n" $BASE/actuator/health
curl -s $BASE/actuator/health
```

Expect `200` and `{"status":"UP"}`.

### T2 — Categories

```bash
curl -s $BASE/api/finance/categories | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d)); print(d[0])"
```

Expect `14` and `{'name': 'HOUSING_AND_RENT', 'label': 'Housing & Rent', 'color': '#4F46E5'}`.

### T3 — Analyze a PDF (blocking)

```bash
curl -s -X POST -F "file=@$FILE" $BASE/api/finance/analyze | python3 -m json.tool | head -60
```

Checks: status 200; `transactions` is non-empty; `totalChunks` ≥ 1; `failedChunks` = 0; every `category` is one of the 14 values; credits (for example *Received from Razorpay*) have `category` `INCOME_AND_TRANSFERS`; `utr` values are 12 digits.

### T4 — Analyze a CSV

```bash
curl -s -X POST -F "file=@/tmp/statement.csv" $BASE/api/finance/analyze \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['totalExpense'], r['totalIncome'], r['netFlow'], r['uncategorizedCount'])"
```

Expect `2599.0 85000.0 82401.0` and an uncategorized count of at least 1.

### T5 — Unsupported type

```bash
echo "hello" > /tmp/notes.txt
curl -s -w "\nHTTP %{http_code}\n" -X POST -F "file=@/tmp/notes.txt" $BASE/api/finance/analyze
```

Expect `HTTP 422` and `{"message":"Unsupported file type. Upload a PDF, CSV, XLSX or XLS statement."}`.

### T6 — Empty file

```bash
: > /tmp/empty.csv
curl -s -w "\nHTTP %{http_code}\n" -X POST -F "file=@/tmp/empty.csv" $BASE/api/finance/analyze
```

Expect `HTTP 422`, `Please upload a non-empty file.`

### T7 — Missing file part

```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST -F "password=x" $BASE/api/finance/analyze
```

Expect an error status and a JSON `message`. The controller has no dedicated handler for a missing part, so it falls through to the generic handler (500 with the generic message). Treat a 4xx here as an improvement, not a regression.

### T8 — Too large

```bash
head -c 21000000 /dev/zero > /tmp/big.csv
curl -s -w "\nHTTP %{http_code}\n" -X POST -F "file=@/tmp/big.csv" $BASE/api/finance/analyze
```

Expect `HTTP 413`, `File is larger than 20 MB.`

### T9 — Password-protected PDF

Create a locked copy (needs `qpdf`):

```bash
qpdf --encrypt secret secret 256 -- $FILE /tmp/locked.pdf
curl -s -w "\nHTTP %{http_code}\n" -X POST -F "file=@/tmp/locked.pdf" $BASE/api/finance/analyze
curl -s -o /dev/null -w "with password: HTTP %{http_code}\n" -X POST -F "file=@/tmp/locked.pdf" -F "password=secret" $BASE/api/finance/analyze
```

Expect 422 `This PDF is password protected...` first, then 200 with the password.

### T10 — Start async job

```bash
curl -s -X POST -F "file=@$FILE" $BASE/api/finance/analyze/async -w "\nHTTP %{http_code}\n"
```

Expect `HTTP 202` and a body with `jobId`, `totalChunks`, `chunkIds`, `eventsUrl`.

### T11 — Follow progress (SSE)

```bash
JOB=$(curl -s -X POST -F "file=@$FILE" $BASE/api/finance/analyze/async | python3 -c "import sys,json; print(json.load(sys.stdin)['jobId'])")
curl -N $BASE/api/finance/jobs/$JOB/events
```

Expect, in order: `progress` (`CHUNKED`), `progress` (`CHUNK_STARTED`/`CHUNK_COMPLETED` per chunk), `progress` (`SUMMARIZING`), `progress` (`COMPLETED`), then `result`. `percent` never decreases. The command exits when the stream closes.

### T12 — Replay after completion

Run the stream command again with the same `$JOB` within 15 minutes.

```bash
curl -N $BASE/api/finance/jobs/$JOB/events
```

Expect the identical full history and result again, then the stream closes. After 15 minutes the job is evicted and the call returns 404.

### T13 — Unknown job

```bash
curl -s -w "\nHTTP %{http_code}\n" $BASE/api/finance/jobs/doesnotexist/events
```

Expect `HTTP 404` and `{"message":"Unknown or expired analysis job: doesnotexist"}`.

### T14 — Cancel

Use a large statement (many chunks) so the job is still running:

```bash
JOB=$(curl -s -X POST -F "file=@big_statement.pdf" $BASE/api/finance/analyze/async | python3 -c "import sys,json; print(json.load(sys.stdin)['jobId'])")
curl -N $BASE/api/finance/jobs/$JOB/events &
sleep 2
curl -s -w "HTTP %{http_code}\n" -X DELETE $BASE/api/finance/jobs/$JOB
```

Expect `HTTP 204` and a `cancelled` event on the stream. No `result` follows.

### T15 — Cancel unknown job

```bash
curl -s -w "\nHTTP %{http_code}\n" -X DELETE $BASE/api/finance/jobs/doesnotexist
```

Expect `HTTP 404`.

### T16 — Invalid OpenAI key

Restart with a bad key and upload any statement:

```bash
OPENAI_API_KEY=sk-invalid mvn spring-boot:run
curl -s -w "\nHTTP %{http_code}\n" -X POST -F "file=@$FILE" $BASE/api/finance/analyze
```

Expect `HTTP 502` with the generic message `We couldn't analyze this statement right now...`. The log shows `OpenAI returned HTTP 401`. Without any key the app does not start at all.

## 3. Reusable HTTP file

Works with the VS Code REST Client extension or IntelliJ HTTP Client. Save as `api.http`.

```http
@base = http://localhost:9090

### Health
GET {{base}}/actuator/health

### Categories
GET {{base}}/api/finance/categories

### Analyze (blocking)
POST {{base}}/api/finance/analyze
Content-Type: multipart/form-data; boundary=B

--B
Content-Disposition: form-data; name="file"; filename="PhonePe_Test_Statement_Short.pdf"
Content-Type: application/pdf

< ./samples/PhonePe_Test_Statement_Short.pdf
--B--

### Analyze (async)
POST {{base}}/api/finance/analyze/async
Content-Type: multipart/form-data; boundary=B

--B
Content-Disposition: form-data; name="file"; filename="PhonePe_Test_Statement_Short.pdf"
Content-Type: application/pdf

< ./samples/PhonePe_Test_Statement_Short.pdf
--B--

### Follow job (replace the id)
GET {{base}}/api/finance/jobs/REPLACE_ME/events

### Cancel job (replace the id)
DELETE {{base}}/api/finance/jobs/REPLACE_ME
```

## 4. Postman

1. New request, method `POST`, URL `{{base}}/api/finance/analyze`.
2. Body → form-data → key `file` (type **File**) → choose the sample PDF. Add `password` (type Text) only for locked PDFs.
3. For SSE, use a GET request to `.../events`; Postman shows events as they arrive.

## 5. Automated tests (no API key needed)

```bash
mvn test
```

| Test class | Covers |
|---|---|
| `OpenAiLlmClientTest` | Request shape, error handling, temperature retry |
| `StructuredLlmServiceTest` | Retries, JSON extraction, tolerant parsing |
| `FinanceAiServiceTest` | Full pipeline, totals, failed-chunk handling, narrative fallback |
| `StatementChunkingTest` | Record merging, chunk sizes |
| `TransactionAccumulatorTest` | Validation, UTR de-duplication |
| `CategoryRulesTest` | Keyword corrections, credits forced to income |
| `LocalInsightsTest` | Fallback summary and tips |
| `ProgressTrackerTest` | Percentage maths |

## 6. Verification checklist

- [ ] T1–T2 pass without an API key being used
- [ ] CSV totals match the expected numbers exactly
- [ ] Self-transfer excluded from both totals
- [ ] Every returned category is one of the 14 values
- [ ] Duplicate UTRs appear only once
- [ ] SSE `percent` is monotonic and ends at 100
- [ ] Error statuses and messages match the table in [api-reference.md](api-reference.md)
