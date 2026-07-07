# Blast Radius

A stateless Spring Boot service that analyzes pull-request diffs with an LLM
(Groq, OpenAI-compatible API) and reports the **blast radius** of a change:
an overall risk level, the components it impacts, and the tests worth running.

A companion GitHub Actions workflow posts the analysis as a PR comment on every
push, so reviewers see risk and test suggestions without leaving the PR.

## How it works

```
POST /analysis/pr  { baseBranch, targetBranch, diff }
  → AnalysisController          (thin, delegates only)
    → AnalysisService.analyze()
        · validates the diff, truncates oversized ones (32 KB, critical files first)
        · renders the prompt and calls GroqClient
        · parses the model's JSON into PrAnalysisResponse
  ← 200  { overallRisk, impactAreas, suggestedTests, analysisId, promptVersion, modelName }
```

Every failure path degrades gracefully: upstream/network errors come back as
`overallRisk: "ERROR_UPSTREAM"`, malformed input or unparseable model output as
`overallRisk: "PARSING_ERROR"` — always HTTP 200 with the same JSON shape, so CI
consumers never need special error handling.

## Quick start

Requires **JDK 17+** (Maven wrapper included).

```bash
export GROQ_API_KEY=your-key-here
./mvnw spring-boot:run          # listens on :8090
```

Try it:

```bash
curl -s -X POST http://localhost:8090/analysis/pr \
  -H "Content-Type: application/json" \
  -d '{
        "baseBranch": "main",
        "targetBranch": "feature/order-status",
        "diff": "diff --git a/OrderStatus.java ... +RETURNED"
      }'
```

## Configuration

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `groq.api.key` | `GROQ_API_KEY` | — (required) | Groq API key |
| `groq.api.url` | | Groq chat completions URL | Upstream endpoint |
| `groq.api.model` | | `qwen/qwen3-32b` | Model name |
| `groq.api.connect-timeout-millis` | | `5000` | HTTP connect timeout |
| `groq.api.read-timeout-millis` | | `20000` | Per-request read timeout |
| `groq.api.max-attempts` | | `4` | Retries on 5xx/429/network errors (exponential backoff + jitter, honors `Retry-After`) |
| `blast-radius.api-key` | `BLAST_RADIUS_API_KEY` | blank (auth off) | Shared secret for `/analysis/*` via `X-API-Key` or `Authorization: Bearer`; **set it anywhere non-local** |
| `blast-radius.max-request-bytes` | | `1048576` | Bodies larger than this are rejected with 413 |
| `blast-radius.max-concurrent-analyses` | | `8` | In-flight analysis cap; excess requests get 429 |
| `server.port` | | `8090` | HTTP port |

Health and metrics (including per-risk analysis counters) are exposed via
Spring Actuator: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`.

## CI integration

`.github/workflows/blast-radius.yml` runs on every PR: it computes the diff,
calls the service, and posts (or updates) a risk-report comment. Configure two
repository secrets:

- `BLAST_RADIUS_URL` — full endpoint URL, e.g. `https://host/analysis/pr`
- `BLAST_RADIUS_API_KEY` — only if the service enforces auth

The check is advisory: if the service is unreachable, the workflow posts an
"analysis unavailable" note instead of failing the PR.

## Testing

```bash
./mvnw test                     # full suite; no external dependencies needed
```

With `GROQ_API_KEY` exported, the golden regression suite (`GoldenDiffTest`)
also runs: curated diffs in `src/test/resources/golden-diffs/` are analyzed
against the live model and compared to expected results in `golden-results/`.
Regenerate expectations after intentional prompt changes with:

```bash
GROQ_API_KEY=... ./mvnw test -Dtest=GoldenDiffTest -Dgolden.update=true
```

## Project layout

- `controller/` — REST endpoints only
- `service/` — orchestration, prompt construction, error mapping
- `infra/` — Groq HTTP client (retries, timeouts)
- `model/` — request/response DTOs and the `OverallRisk` enum
- `util/` — stateless helpers (JSON sanitizing/parsing, diff prioritization)
- `web/` — servlet filters (auth, size limit, concurrency) and exception handling
