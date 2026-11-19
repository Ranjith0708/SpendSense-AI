# SpendSense AI

**SpendSense AI** is a personal-finance analyzer for Indian bank and UPI statements. Upload a statement from PhonePe, Google Pay, Paytm, ICICI, HDFC, SBI or similar, and get your spending sorted into 14 categories, exact totals, a plain-language summary, and practical ways to save money. Language understanding comes from the OpenAI API; all totals and percentages are calculated in Java so the numbers are exact.

## Highlights

- Upload PDF, CSV, XLSX or XLS (up to 20 MB, password-protected PDFs supported)
- AI extracts and categorises every transaction, aware of truncated UPI names
- Self-transfers and duplicates (by UTR) handled automatically
- Live progress over Server-Sent Events, with cancel
- Dashboard with charts, searchable transactions, insights and savings tips
- One-click PDF report
- Graceful fallbacks when part of the AI work fails

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 17 |
| Framework | Spring Boot 3.4.3 (Web, Actuator) |
| AI | OpenAI Chat Completions API (JSON mode, JDK `HttpClient`), default model `gpt-4o-mini` |
| PDF parsing | Apache PDFBox 3.0.3 |
| CSV parsing | Apache Commons CSV 1.11.0 |
| Excel parsing | Apache POI 5.3.0 |
| Utilities | Lombok |
| Live progress | Server-Sent Events (SSE) |
| Frontend | Vanilla HTML5, CSS3, JavaScript |
| Charts / PDF report | Chart.js, jsPDF + jspdf-autotable |
| Build | Maven |
| Tests | JUnit 5 (Spring Boot Starter Test) |
| Packaging | Docker (multi-stage, non-root) |

## Quick start

```bash
export OPENAI_API_KEY="sk-..."
mvn spring-boot:run
```

Open http://localhost:9090 and upload `samples/PhonePe_Test_Statement_Short.pdf`.
Health check: http://localhost:9090/actuator/health

Full run, configuration, Docker and troubleshooting steps are in the runbook below.

## Documentation

All documentation lives in [`samples/docs`](samples/docs):

| Read this | For |
|---|---|
| [samples/docs/overview.md](samples/docs/overview.md) | What the project is, features, scope and limits |
| [samples/docs/architecture-and-flows.md](samples/docs/architecture-and-flows.md) | Components, sequence and flow diagrams, design decisions |
| [samples/docs/api-reference.md](samples/docs/api-reference.md) | Endpoints, request/response schemas, SSE events, errors |
| [samples/docs/rest-api-tests.md](samples/docs/rest-api-tests.md) | curl / HTTP test cases and the automated test suite |
| [samples/docs/runbook.md](samples/docs/runbook.md) | How to build, run, configure, deploy and troubleshoot |
| [samples/docs/ai-usage.md](samples/docs/ai-usage.md) | How AI is used: prompts, guardrails, cost, privacy |

## Sample input

[`samples/PhonePe_Test_Statement_Short.pdf`](samples/PhonePe_Test_Statement_Short.pdf): a short PhonePe statement for trying the app.

## Project structure

```
src/main/java/com/finance/analyzer
├── controller/   REST endpoints
├── service/      Parsing, chunking, categorisation rules, jobs, totals, insights
├── llm/          OpenAI client
├── config/       Typed configuration
├── dto/          Request / response records
├── model/        ExpenseCategory enum
└── exception/    Error handling
src/main/resources/static   Dashboard (HTML, CSS, JS)
samples/                    Sample statement and documentation (docs/)
```

## Privacy

Statement text is sent to the OpenAI API for analysis. Nothing is stored by the application; data is processed in memory. Do not upload statements you are not comfortable sharing with OpenAI, and keep `LOG_PAYLOADS=false`.
