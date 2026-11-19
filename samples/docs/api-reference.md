# API Reference

Base URL (local): `http://localhost:9090`
All JSON responses use UTF-8. Errors always have the shape `{"message": "<text>"}`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/finance/analyze` | Upload and wait for the final result (blocking) |
| `POST` | `/api/finance/analyze/async` | Start a background job (used by the UI) |
| `GET` | `/api/finance/jobs/{jobId}/events` | SSE stream of progress and the final result |
| `DELETE` | `/api/finance/jobs/{jobId}` | Cancel a running job |
| `GET` | `/api/finance/categories` | List the 14 categories |
| `GET` | `/actuator/health` | Health check |

---

## POST /api/finance/analyze

Parses the statement, analyses every chunk, and returns the complete result in one response. For large statements this can take minutes, so prefer the async flow for anything interactive.

**Request:** `multipart/form-data`

| Field | Type | Required | Notes |
|---|---|---|---|
| `file` | file | yes | `.pdf`, `.csv`, `.xlsx`, `.xls`; max 20 MB |
| `password` | text | no | Only for password-protected PDFs |

**Response 200:** [`AnalysisResponse`](#analysisresponse)

**Errors:** 413, 422, 502, 500 (see [Errors](#errors)).

---

## POST /api/finance/analyze/async

Parses the file immediately, then runs the analysis in the background.

**Request:** same as above.

**Response 202:**

```json
{
  "jobId": "a1b2c3d4",
  "totalChunks": 3,
  "chunkIds": ["chunk_1", "chunk_2", "chunk_3"],
  "eventsUrl": "/api/finance/jobs/a1b2c3d4/events"
}
```

Parse problems (wrong type, scan, wrong password) return `422` here, before any job exists.

---

## GET /api/finance/jobs/{jobId}/events

Server-Sent Events (`text/event-stream`). Responses carry `Cache-Control: no-store` and `X-Accel-Buffering: no`.

| Event | Data | Stream |
|---|---|---|
| `progress` | [`ProgressEvent`](#progressevent) | stays open |
| `result` | [`AnalysisResponse`](#analysisresponse) | closes |
| `failure` | `{"message": "..."}` | closes |
| `cancelled` | `{"message": "Analysis cancelled"}` | closes |
| *(comment)* `: keep-alive` | none | every 15 s |

Behaviour:

- Full history is replayed to every new subscriber, so late joins and reconnects are safe.
- Multiple clients can watch the same job.
- Unknown or expired (older than 15 minutes after finishing) job returns `404`.

Example stream:

```
event:progress
data:{"jobId":"a1b2c3d4","stage":"CHUNKED","chunkId":null,"completedChunks":0,"failedChunks":0,"totalChunks":3,"percent":0,"message":"Statement split into 3 chunks"}

event:progress
data:{"jobId":"a1b2c3d4","stage":"CHUNK_STARTED","chunkId":"chunk_1","completedChunks":0,"failedChunks":0,"totalChunks":3,"percent":0,"message":"Processing chunk_1 of 3"}

event:progress
data:{"jobId":"a1b2c3d4","stage":"CHUNK_COMPLETED","chunkId":"chunk_1","completedChunks":1,"failedChunks":0,"totalChunks":3,"percent":33,"message":"chunk_1 done"}

event:progress
data:{"jobId":"a1b2c3d4","stage":"SUMMARIZING","chunkId":null,"completedChunks":3,"failedChunks":0,"totalChunks":3,"percent":100,"message":"Calculating totals and writing your summary"}

event:progress
data:{"jobId":"a1b2c3d4","stage":"COMPLETED","chunkId":null,"completedChunks":3,"failedChunks":0,"totalChunks":3,"percent":100,"message":"Done"}

event:result
data:{ ...AnalysisResponse... }
```

---

## DELETE /api/finance/jobs/{jobId}

Cancels a running job and interrupts in-flight OpenAI calls. Subscribers receive `cancelled`.

**Response:** `204 No Content`. Unknown job: `404`. Cancelling an already finished job is harmless.

---

## GET /api/finance/categories

**Response 200:**

```json
[
  {"name": "HOUSING_AND_RENT", "label": "Housing & Rent", "color": "#4F46E5"},
  {"name": "FOOD_AND_DINING", "label": "Food & Dining", "color": "#EA580C"}
]
```

The list contains all 14 categories (below), in this order.

| Name | Label | Colour |
|---|---|---|
| `HOUSING_AND_RENT` | Housing & Rent | `#4F46E5` |
| `UTILITIES_AND_BILLS` | Utilities & Bills | `#0891B2` |
| `GROCERIES_AND_HOUSEHOLD` | Groceries & Household | `#16A34A` |
| `FOOD_AND_DINING` | Food & Dining | `#EA580C` |
| `TRANSPORTATION_AND_FUEL` | Transportation & Fuel | `#CA8A04` |
| `SHOPPING_AND_PERSONAL_CARE` | Shopping & Personal Care | `#DB2777` |
| `HEALTH_AND_MEDICAL` | Health & Medical | `#DC2626` |
| `ENTERTAINMENT_AND_SUBSCRIPTIONS` | Entertainment & Subscriptions | `#9333EA` |
| `EDUCATION_AND_LEARNING` | Education & Learning | `#2563EB` |
| `TRAVEL_AND_VACATIONS` | Travel & Vacations | `#0D9488` |
| `DEBT_AND_EMIS` | Debt & EMIs | `#7F1D1D` |
| `INVESTMENTS_AND_SAVINGS` | Investments & Savings | `#15803D` |
| `INCOME_AND_TRANSFERS` | Income & Transfers | `#64748B` |
| `OTHER_UNCATEGORIZED` | Other / Uncategorized | `#94A3B8` |

---

## GET /actuator/health

Returns `{"status":"UP"}` when the app is running. Only the `health` endpoint is exposed.

---

## Schemas

### AnalysisResponse

| Field | Type | Description |
|---|---|---|
| `summary` | string | 2–3 sentence overview |
| `totalExpense` | number | Sum of real spending (debits, excluding self-transfers) |
| `totalIncome` | number | Sum of credits, excluding self-transfers |
| `netFlow` | number | `totalIncome - totalExpense` |
| `uncategorizedCount` | integer | Spending rows in `OTHER_UNCATEGORIZED` |
| `categoryBreakdown` | [CategorySummary] | Sorted by amount, highest first |
| `transactions` | [Transaction] | In statement order, de-duplicated |
| `insights` | [string] | Observations about spending |
| `feedback` | [string] | Budgeting recommendations |
| `savingsTips` | [string] | Concrete ways to save |
| `totalChunks` | integer | Chunks the statement was split into |
| `failedChunks` | integer | Chunks that failed after all retries |
| `warnings` | [string] | User-facing notes (partial data, AI summary fallback) |

### Transaction

| Field | Type | Description |
|---|---|---|
| `date` | string | As printed on the statement; `yyyy-MM-dd` when certain |
| `details` | string | Cleaned payee or merchant |
| `utr` | string | 12-digit UTR, or empty |
| `amount` | number | Always positive |
| `type` | `DEBIT` \| `CREDIT` | Direction of money |
| `category` | enum | One of the 14 categories |
| `selfTransfer` | boolean | True for transfers between your own accounts |

### CategorySummary

| Field | Type | Description |
|---|---|---|
| `category` | enum | Category name |
| `displayLabel` | string | Human label |
| `color` | string | Hex colour for charts |
| `transactionCount` | integer | Spending rows in this category |
| `totalAmount` | number | Total spent |
| `percentage` | number | Share of `totalExpense`, one decimal |

### ProgressEvent

| Field | Type | Description |
|---|---|---|
| `jobId` | string | Job identifier |
| `stage` | enum | `CHUNKED`, `CHUNK_STARTED`, `CHUNK_COMPLETED`, `CHUNK_FAILED`, `SUMMARIZING`, `COMPLETED` |
| `chunkId` | string \| null | Chunk concerned; null for job-level events |
| `completedChunks` | integer | Finished chunks (success or failure) |
| `failedChunks` | integer | Failed chunks |
| `totalChunks` | integer | Total chunks |
| `percent` | integer | 0–100, never decreases |
| `message` | string | Short status text |

### Complete example

```json
{
  "summary": "You spent ₹2,312 across 6 payments. Groceries & Household was the biggest category at 42.1% of your spending.",
  "totalExpense": 2312.0,
  "totalIncome": 0.0,
  "netFlow": -2312.0,
  "uncategorizedCount": 2,
  "categoryBreakdown": [
    {"category": "GROCERIES_AND_HOUSEHOLD", "displayLabel": "Groceries & Household", "color": "#16A34A",
     "transactionCount": 2, "totalAmount": 995.0, "percentage": 43.0}
  ],
  "transactions": [
    {"date": "2026-10-21", "details": "Swiggy", "utr": "731181712162", "amount": 845.0,
     "type": "DEBIT", "category": "FOOD_AND_DINING", "selfTransfer": false}
  ],
  "insights": ["..."],
  "feedback": ["..."],
  "savingsTips": ["..."],
  "totalChunks": 1,
  "failedChunks": 0,
  "warnings": []
}
```

(Values are illustrative; real output depends on the statement and model.)

---

## Errors

| Status | When | Example message |
|---|---|---|
| 202 | Async job accepted | n/a |
| 204 | Job cancelled | n/a |
| 404 | Unknown or expired job | `Unknown or expired analysis job: a1b2c3d4` |
| 413 | File over 20 MB | `File is larger than 20 MB.` |
| 422 | Unreadable or unsupported input | `Unsupported file type. Upload a PDF, CSV, XLSX or XLS statement.` |
| 422 | Empty file | `Please upload a non-empty file.` |
| 422 | Scanned PDF | `No readable text found. Scanned or image-only statements are not supported; download the digital statement instead.` |
| 422 | Locked PDF | `This PDF is password protected. Enter the correct password and retry.` |
| 422 | Corrupt file | `Could not read the file. It may be corrupted or in an unsupported format.` |
| 502 | AI failed on every chunk | `We couldn't analyze this statement right now. Please try again in a moment.` |
| 502 | No transactions found | `No transactions could be identified in this statement.` |
| 500 | Unexpected | `Something went wrong while analysing the statement.` |

Over SSE, failures arrive as a `failure` event with the same message text rather than an HTTP status.

## Authentication, CORS and limits

- No authentication is built in. Put the app behind a gateway or VPN for shared use.
- No CORS configuration; the UI is served from the same origin.
- Upload limit: 20 MB per file and per request.
- At most 2 jobs run concurrently by default; extra jobs wait for a free slot.
