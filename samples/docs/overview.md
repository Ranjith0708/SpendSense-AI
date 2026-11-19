# SpendSense AI — Project Overview

SpendSense AI turns a raw bank or UPI statement into a clear picture of where your money went. You upload a statement (PhonePe, Google Pay, Paytm, ICICI, HDFC, SBI and similar), and the app returns categorised transactions, exact totals, and plain-language advice on how to save.

## The problem it solves

Statements are long, inconsistent and hard to read. UPI payee names are truncated (`BMRCL`, `RAILTEL CO`), personal transfers are mixed with merchant payments, and tiny recurring payments add up unnoticed. Most people never summarise them.

## What it does

| Step | Who does it | Result |
|---|---|---|
| 1. Read the file | Java (PDFBox / Commons CSV / POI) | Clean text from PDF, CSV, XLSX or XLS |
| 2. Split into chunks | Java | Whole transaction records, about 25 per chunk |
| 3. Extract and categorise | OpenAI (one request per chunk) | Structured transactions with one of 14 categories |
| 4. Correct and merge | Java | Keyword corrections, de-duplication by UTR |
| 5. Calculate totals | Java | Exact expense, income, net flow, category shares |
| 6. Write the advice | OpenAI (one request) | Summary, insights, budgeting feedback, savings tips |
| 7. Show and export | Browser | Dashboard and a downloadable PDF report |

The key design rule: **the AI reads and writes language, Java does the maths.** Every number a user sees is computed in code, never by the model.

## Who it is for

- Individuals who want a monthly spending review without manual spreadsheets.
- Developers who want a reference for a production-style LLM pipeline (chunking, retries, structured output, fallbacks, live progress).

## Key features

- Upload PDF, CSV, XLSX or XLS up to 20 MB, including password-protected PDFs.
- 14 spending categories with Indian UPI awareness (truncated names, self-transfers).
- Self-transfers excluded from both spending and income.
- Duplicate protection using the 12-digit UTR.
- Live progress bar over Server-Sent Events, with cancel.
- Dashboard: KPI cards, doughnut chart, top payees, largest payments, searchable transaction table.
- AI summary, insights, budgeting feedback and savings tips.
- One-click PDF report.
- Graceful degradation: failed chunks are reported, and if the AI summary fails the app generates one locally.

## Scope and limits

- Digital statements only. Scanned or image-only PDFs are rejected because they contain no text.
- No database. Statements are processed in memory; finished jobs are kept for 15 minutes for result replay, then dropped.
- No user accounts or authentication. Intended for local or single-tenant use unless you add a gateway in front.
- The statement text is sent to the OpenAI API. See the privacy note in the [AI usage guide](ai-usage.md).

## Tech stack at a glance

Java 17, Spring Boot 3.4.3, OpenAI Chat Completions (JSON mode, JDK `HttpClient`), Apache PDFBox 3.0.3, Apache Commons CSV 1.11.0, Apache POI 5.3.0, Lombok, Server-Sent Events, vanilla HTML/CSS/JS, Chart.js, jsPDF with autotable, Maven, JUnit 5, Docker.

## Documentation map

| Document | Read it for |
|---|---|
| [overview.md](overview.md) | This page: what the project is |
| [architecture-and-flows.md](architecture-and-flows.md) | Components, data flow, sequence diagrams |
| [api-reference.md](api-reference.md) | Endpoints, payloads, errors, SSE events |
| [rest-api-tests.md](rest-api-tests.md) | Ready-to-run curl and HTTP test cases |
| [runbook.md](runbook.md) | How to build, run, configure, deploy, troubleshoot |
| [ai-usage.md](ai-usage.md) | How AI is used, prompts, guardrails, cost and privacy |

## Sample input

`samples/PhonePe_Test_Statement_Short.pdf` is a short PhonePe statement (Oct 18 – Nov 17, 2026) you can upload to try the app.
