# Spring AI Ops

[한국어(Korean) 문서](README_KR.md)  
  
An AI-powered operations automation tool that receives webhooks from **Grafana Alerting**, **GitHub**, and **GitLab**, then uses an LLM (OpenAI, Anthropic) to analyze errors, review code, and perform static code risk analysis in real time. For Grafana alerts, the application queries Loki logs and, when a Prometheus URL is configured, also fetches Prometheus metrics over the same alert window before sending the combined context to the LLM. Results are delivered to a live dashboard via WebSocket.  

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Interface Flows](#interface-flows)
  - [Static Code Risk Analysis](#static-code-risk-analysis)
  - [GitHub → LLM](#github--llm-code-review)
  - [GitLab → LLM](#gitlab--llm-code-review)
  - [Grafana → Loki + Prometheus → LLM](#grafana--loki--prometheus--llm-error-analysis)
- [Screenshots](#screenshots)
- [Technology Stack](#technology-stack)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Configuration](#configuration)
  - [Sensitive Value Encryption](#sensitive-value-encryption)
  - [Running](#running)
  - [Setting Up Grafana](#setting-up-grafana)
  - [Setting Up GitHub Webhooks](#setting-up-github-webhooks)
  - [Setting Up GitLab Webhooks](#setting-up-gitlab-webhooks)
- [API Reference](#api-reference)
- [API Documentation (Swagger)](#api-documentation-swagger)
- [Package Structure](#package-structure)
- [License](#license)

---

## Overview

Spring AI Ops bridges your monitoring and version-control toolchain with large language models. It covers three distinct AI-powered workflows:

1. **Static Code Risk Analysis** — On demand, Spring AI Ops clones a registered Git repository, scans the entire source tree, and sends the bundled code to an LLM for a full security and quality review. For large codebases the analysis is split into chunks and processed in parallel (map-reduce), then consolidated into a single final report. Results include a markdown report and a structured JSON issue list (severity, file, line, recommendation).

2. **Automated Code Review** — When a GitHub or GitLab push webhook arrives, the application fetches the commit diff and sends it to the LLM for an automated code review covering correctness, security, performance, and code quality.

3. **Incident Intelligence** — When Grafana fires an alert, the application builds a Loki stream selector from alert labels, queries matching logs, and in parallel optionally queries Prometheus range data using the same alert labels and time window. The alert context, log lines, and metric series are sent together to an LLM to produce a richer root-cause analysis, streamed to the dashboard in real time.

All results are pushed to connected browsers in real time via STOMP WebSocket.

No relational database is used. Redis serves as the sole persistence layer — storing LLM configuration, application registry, alert analysis records, and code review records.

> **Live Demo**: [https://ai-ops.duckdns.org](https://ai-ops.duckdns.org)

---

## Key Features

| Feature | Description |
|---|---|
| **Static Code Risk Analysis** | Clone a Git repository and run an AI-powered full-codebase review — security vulnerabilities, code quality issues, and actionable recommendations. Supports single-call and map-reduce strategies based on codebase size |
| **Automated Code Review** | GitHub / GitLab commit diff → code quality, potential bugs, security considerations |
| **LLM-Powered Error Analysis** | Grafana alert context + Loki logs + Prometheus metric series (optional) → root cause, affected components, and recommended actions |
| **Real-Time Dashboard** | WebSocket STOMP push to browser on analysis completion |
| **Dynamic LLM Configuration** | Switch between OpenAI and Anthropic at runtime via the UI — no restart required |
| **Multi-Application** | Register multiple application names; analysis history is scoped per application |
| **Zero-RDB Design** | Redis is the only data store; embedded Redis starts automatically in local dev |
| **Virtual Thread Executor** | Webhook handlers return immediately; analysis runs on Java 21 virtual threads. LLM API calls are rate-limited via a dedicated Semaphore (default: 20 concurrent) |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Browser (SPA)                           │
│  Mustache + STOMP/SockJS  ←─── WebSocket /topic/firing         │
│                           ←─── WebSocket /topic/commit         │
└───────────────┬─────────────────────────────────────────────────┘
                │ HTTP
┌───────────────▼─────────────────────────────────────────────────┐
│                  Spring Boot Application                        │
│                                                                 │
│  WebhookController  ──►  AnalyzeFacade                         │
│  (POST /webhook/*)        │                                     │
│                           ├─ ApplicationService  ──► Redis      │
│  AiConfigController       ├─ GrafanaService      ──► Redis           │
│  LokiConfigController     ├─ GithubService       ──► Redis           │
│  ApplicationController    ├─ GitlabService       ──► Redis           │
│  FiringController         ├─ LokiService         ──► Loki API        │
│  CommitController         ├─ PrometheusService   ──► Prometheus API  │
│                           ├─ GithubConnector     ──► GitHub API      │
│                           ├─ GitlabConnector     ──► GitLab API      │
│                           └─ AiModelService      ──► LLM API        │
│                                    │                            │
│                           SimpMessagingTemplate                 │
│                                    │                            │
└────────────────────────────────────┼────────────────────────────┘
                                     │ WebSocket push
                              Connected browsers
```

**Layering rules**

- **Controller** — receives HTTP request and returns DTO; no business logic.
- **Facade** — orchestrates multiple services for a single use-case; marked `@Facade`.
- **Service** — single-responsibility business logic and Redis persistence.
- **Connector** — OpenFeign clients for Loki, Prometheus, GitHub, and GitLab REST APIs.

---

## Interface Flows

### Static Code Risk Analysis

```
User clicks "Run Static Analysis" in the dashboard
        │
        ▼
POST /api/code-risk
        │
        ├─ Look up registered Git repository URL for the application
        │
        ├─ Resolve access token (GitHub or GitLab token from Redis)
        │
        ├─ Clone repository via JGit (with token auth if available)
        │    specified branch, or default branch if blank
        │
        ├─ Collect source files and build a code bundle
        │
        ├─ Estimate token count
        │    ≤ token-threshold (default: 27,000)
        │      → Single-call analysis
        │           Call LLM once with the full bundle
        │    > token-threshold
        │      → Map-reduce analysis
        │           Split into chunks → analyze each chunk in parallel
        │           (max concurrency: 3, delay: 1,000 ms between calls)
        │           Consolidate chunk results with a final LLM call
        │
        ├─ Parse LLM response
        │    Markdown analysis  (overall summary, recommendations)
        │    Issues JSON        (file, line, severity, description, codeSnippet)
        │
        ├─ Save CodeRiskRecord to Redis  (key: code-risk:{application})
        │
        ├─ Push progress messages to /topic/analysis/status via WebSocket
        │
        └─ Push completion notification to /topic/analysis/result via WebSocket
                │
                ▼
           Browser opens Code Risk tab with full analysis result
```

> **Note**: Analysis progress (cloning, chunk status, consolidation) is streamed to the dashboard in real time via WebSocket. If the LLM returns a rate-limit error (429) mid-analysis, the facade stops and returns partial results gathered up to that point.

> **Git Authentication**: The access token configured under Git Remote Configuration (GitHub or GitLab) is used automatically for private repository cloning. No additional setup is required.

---

### GitHub → LLM (Code Review)

```
git push to repository
        │
        ▼  (GitHub Webhook)
POST /webhook/git[/{application}]
        │
        ├─ Extract owner / repo / before SHA / after SHA from payload
        │
        ├─ Call GitHub Commits API
        │    before == 0000...0000 (initial push) → GET /repos/{owner}/{repo}/commits/{sha}
        │    otherwise                             → GET /repos/{owner}/{repo}/compare/{base}...{head}
        │
        ├─ Call LLM
        │    System: expert code reviewer
        │    User:   diff per changed file
        │            → summary / issues / security / suggestions
        │
        ├─ Save CodeReviewRecord to Redis  (key: commit:{application})
        │
        └─ Push to /topic/commit via WebSocket
                │
                ▼
           Browser opens Code Review tab with result
```

---

### GitLab → LLM (Code Review)

```
git push to repository
        │
        ▼  (GitLab Webhook)
POST /webhook/git[/{application}]
        │
        ├─ Detected by X-Gitlab-Event header → parsed as GitLab payload
        │
        ├─ Extract project / before SHA / after SHA from payload
        │
        ├─ Call GitLab Repository API
        │    before == 0000...0000 (initial push) → GET /projects/{id}/repository/commits/{sha}/diff
        │    otherwise                             → GET /projects/{id}/repository/compare?from={base}&to={head}
        │
        ├─ Call LLM
        │    System: expert code reviewer
        │    User:   diff per changed file
        │            → summary / issues / security / suggestions
        │
        ├─ Save CodeReviewRecord to Redis  (key: commit:{application})
        │
        └─ Push to /topic/commit via WebSocket
                │
                ▼
           Browser opens Code Review tab with result
```

---

### Grafana → Loki + Prometheus → LLM (Error Analysis)

```
Grafana Alert fires
        │
        ▼
POST /webhook/grafana[/{application}]
        │
        ├─ status == "resolved"? → skip (return RESOLVED)
        │
        ├─ Pick the first firing alert
        │    (or the first alert in the payload if none are marked firing)
        │
        ├─ Build selectors from alert labels
        │    Loki selector example:       {job="my-app", namespace="prod", pod="api-xyz"}
        │    Prometheus selector example: {job="my-app", namespace="prod", pod="api-xyz"}
        │
        ├─ Calculate time range
        │    start = alert.startsAt − 5 min buffer
        │    end   = alert.endsAt  (current time if zero-value)
        │
        ├─ Query Loki logs
        │    GET {loki.url}/loki/api/v1/query_range
        │    ?query={...}&start=...&end=...
        │
        ├─ Query Prometheus metrics in parallel  ── OPTIONAL (if prometheus.url is configured)
        │    GET {prometheus.url}/api/v1/query_range
        │    Reuses the same alert labels to build the metric selector
        │    and returns all matching series for the alert window
        │
        ├─ Call LLM
        │    System: expert in application errors and logs
        │    User:   alert context
        │            + Loki log lines   (if available)
        │            + Prometheus metric data  (if available)
        │            → root cause / affected components / recommended actions
        │
        ├─ Save AnalyzeFiringRecord to Redis  (key: firing:{application})
        │
        └─ Push to /topic/firing via WebSocket
                │
                ▼
           Browser receives analysis result in real time
```

> **Prerequisite**: Prometheus metric labels and Loki stream labels should share the same identifying labels (`job`, `instance`, `namespace`, `pod`, etc.). Configure Promtail or Grafana Alloy accordingly so the same alert labels can be reused for both queries.

> **Prometheus is optional**: If `prometheus.url` is not set, the analysis proceeds with Loki logs only. No errors are raised.

> **Loki is required for error analysis**: The current Grafana alert pipeline always attempts a Loki query. Configure `loki.url` before using Grafana webhook analysis.

---

## Screenshots

### LLM API Key Configuration
*Enter your LLM provider and API key through the UI. The model is activated immediately without restarting the application.*

![LLM API Key Configuration](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/AIConfig.png?raw=true)

---

### Static Code Risk Analysis
*Run a full AI-powered static analysis on any registered Git repository. Issues are grouped by file with severity levels (HIGH / MEDIUM / LOW), and each entry includes the affected code snippet and a recommended fix.*

![Static Code Risk Analysis](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/CodeRisk.png?raw=true)

---

### AI-Powered Code Review
*When a GitHub push event is received, the LLM reviews the commit diff per changed file and delivers a structured report — covering code quality, potential bugs, security considerations, and improvement suggestions.*

![Code Review](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/CodeReview.png?raw=true)

---

### Grafana Alerting Webhook
*When a Grafana alert fires, the webhook payload is delivered to Spring AI Ops in real time. The alert status and labels are visible in the dashboard.*

![Grafana Alerting Webhook](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/GrafanaAlerting.png?raw=true)

---

### AI-Powered Error Analysis
*The LLM analyzes the Grafana alert context together with Loki logs and, when configured, Prometheus metric series, then streams a root-cause analysis — including affected components and recommended actions — directly to the dashboard.*

![Firing Analysis](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/FiringAnalyze.png?raw=true)

---

## Technology Stack

| Category | Technology |
|---|---|
| Language | Kotlin 2.2 / Java 21 |
| Framework | Spring Boot 3.4.4 |
| AI | Spring AI 1.1.0 — OpenAI (`gpt-4o-mini`), Anthropic (`claude-sonnet-4-6`) |
| Observability | Loki (log queries), Prometheus (metric queries, optional) |
| Persistence | Redis (primary store, no RDBMS) |
| Dev Redis | Embedded Redis (auto-start, no install needed) |
| HTTP Client | Spring Cloud OpenFeign + Resilience4j Circuit Breaker |
| Real-Time | Spring WebSocket (STOMP over SockJS) |
| Templating | Mustache |
| API Docs | springdoc-openapi 2.8.3 (Swagger UI) |
| Async | Java 21 Virtual Threads (`CompletableFuture` + `SimpleAsyncTaskExecutor` concurrency limit: 200) + Semaphore-based LLM rate limiter |
| Build | Gradle Kotlin DSL |

**Design note — Spring AI AutoConfiguration disabled**

All Spring AI `AutoConfiguration` classes are explicitly excluded in `application.yml`. `AiModelService` builds `OpenAiChatModel` / `AnthropicChatModel` directly using `ToolCallingManager.builder().build()`, `RetryUtils.DEFAULT_RETRY_TEMPLATE`, and `ObservationRegistry.NOOP`. This gives full control over model instantiation and allows hot-swapping the LLM provider at runtime.

**Design note — Virtual Thread concurrency**

The `SimpleAsyncTaskExecutor` is capped at **200 concurrent tasks** via `app.async.virtual.executor-concurrency-limit`. This bounds asynchronous work triggered by inbound webhooks while still using Virtual Threads for efficient blocking I/O. A separate `Semaphore` (`app.async.virtual.llm-max-concurrency`, default `10`) further limits only the actual LLM API call inside `AiModelService`, so webhook fan-out and external API work do not translate into unbounded model traffic.

**Design note — Resilience4j TimeLimiter + Virtual Thread compatibility**

Resilience4j's `TimeLimiter` cancels timed-out tasks via `future.cancel(true)`, which calls `Thread.interrupt()`. Virtual Threads handle interrupts differently from platform threads — particularly when pinned to a carrier thread — so the interrupt may not propagate correctly, leaving tasks running past the timeout silently.

To avoid this, `resilience4j.timelimiter.configs.default.cancel-running-future` is set to `false`. This disables the interrupt-based cancellation. Actual I/O timeouts are enforced instead by Feign's own `Request.Options` (`feign.loki.*` / `feign.github.*`), which operate at the socket level and are not affected by the Virtual Thread interrupt issue. The Circuit Breaker state machine (open/half-open/closed) and `FallbackFactory` remain fully active.

---

## Getting Started

### Prerequisites

- JDK 21+
- An API key for at least one LLM provider (OpenAI, Anthropic)
- A running Loki instance for Grafana error analysis
- (Optional) A running Prometheus instance for metric queries
- (Optional) An OTLP-compatible tracing backend or OpenTelemetry Collector for trace export
- A GitHub or GitLab personal access token if you want code review

### Configuration

Edit `src/main/resources/application.yml`:

```yaml
ai:
  open-ai:
    model: gpt-4o-mini                   # OpenAI model name
    api-key: ${AI_OPEN_AI_API_KEY:}      # Or set env var AI_OPEN_AI_API_KEY
  anthropic:
    model: claude-sonnet-4-6             # Anthropic model name
    api-key: ${AI_ANTHROPIC_API_KEY:}    # Or set env var AI_ANTHROPIC_API_KEY
    max-tokens: 8192                     # Max output tokens for Anthropic (default: 8192)

loki:
  url: ${LOKI_URL:}                      # e.g. http://localhost:3100 (authentication is not supported)

prometheus:
  url: ${PROMETHEUS_URL:}                # e.g. http://localhost:9090 (optional — omit to skip metric queries)

github:
  url: ${GITHUB_URL:https://api.github.com}
  access-token: ${GITHUB_ACCESS_TOKEN:}  # GitHub personal access token
  api-version: ${GITHUB_API_VERSION:2022-11-28}  # GitHub API version header

gitlab:
  url: ${GITLAB_URL:https://gitlab.com/api/v4}  # GitLab API base URL (use your self-hosted URL if applicable)
  access-token: ${GITLAB_ACCESS_TOKEN:}          # GitLab personal access token

analysis:
  data-retention-hours: 120  # How long to keep analysis records (default: 5 days)
  maximum-view-count: 5      # Max records shown per application (0 = unlimited)
  result-language: ${ANALYSIS_RESULT_LANGUAGE:en}  # Language of LLM analysis output (e.g. ko, ja, en)
  code-risk:
    token-threshold: 27000        # Max tokens for single-call analysis; larger bundles switch to map-reduce (default: 27000)
    map-reduce-concurrency: 3     # Max parallel chunk analysis calls in map phase (default: 3)
    map-reduce-delay-ms: 1000     # Delay (ms) after each chunk call in map phase (default: 1000)

app:
  async:
    virtual:
      executor-concurrency-limit: 200  # Max concurrent async tasks using virtual threads
      llm-max-concurrency: 20          # Max simultaneous in-flight LLM API calls (Semaphore)

management:
  tracing:
    sampling:
      probability: 1.0  # Trace sampling rate; 1.0 exports all sampled traces in local/dev
  otlp:
    tracing:
      transport: http
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://127.0.0.1:4318/v1/traces}
      export:
        enabled: false  # Set to true to send traces to the OTLP endpoint

resilience4j:
  timelimiter:
    configs:
      default:
        timeout-duration: 35s         # Safety net only — Feign read-timeout (30s) fires first
        cancel-running-future: false  # Prevent Thread.interrupt() on Virtual Threads

feign:
  loki:
    connect-timeout: 5000   # Loki connect timeout (ms)
    read-timeout: 30000     # Loki read timeout (ms)
  prometheus:
    connect-timeout: 5000   # Prometheus connect timeout (ms)
    read-timeout: 30000     # Prometheus read timeout (ms)
  github:
    connect-timeout: 5000   # GitHub API connect timeout (ms)
    read-timeout: 30000     # GitHub API read timeout (ms)
  gitlab:
    connect-timeout: 5000   # GitLab API connect timeout (ms)
    read-timeout: 30000     # GitLab API read timeout (ms)
```

If both a property value and a Redis value exist for the same setting, the Redis value takes precedence.

**Tracing export**

The application includes Spring Boot Actuator and `micrometer-tracing-bridge-otel`, so server requests and instrumented client calls can be converted into OpenTelemetry traces. Set `management.otlp.tracing.export.enabled=true` and point `management.otlp.tracing.endpoint` to your OpenTelemetry Collector, Tempo, Jaeger, or another OTLP-compatible receiver. The default HTTP endpoint is `http://127.0.0.1:4318/v1/traces`; for gRPC, change `management.otlp.tracing.transport` and use the matching collector endpoint.

### Sensitive Value Encryption

API keys and access tokens saved to Redis are encrypted at rest using **AES-256-GCM**.

To enable encryption, set the secret key via environment variable or `application.yml`:

```bash
# Environment variable (recommended for production)
export CRYPTO_SECRET_KEY=your-strong-secret-passphrase
```

```yaml
# application.yml
crypto:
  secret-key: ${CRYPTO_SECRET_KEY:}
```

| Situation | Behaviour |
|---|---|
| `crypto.secret-key` is set | All values written to Redis are AES-256-GCM encrypted |
| `crypto.secret-key` is blank | Values are stored as plaintext — a warning is logged on startup |
| Secret key changes after values are stored | Existing encrypted values cannot be decrypted; re-enter API keys via the UI to re-encrypt them with the new key |

> **Production recommendation**: Always set `CRYPTO_SECRET_KEY` in production environments. Without it, API keys stored in Redis remain in plaintext.

**LLM key auto-configuration behaviour**

| Situation | Result |
|---|---|
| Only one provider key present in yml | That provider is selected automatically on startup — no UI prompt |
| Both provider keys present in yml | A provider-selection modal appears in the UI once |
| No keys in yml | The full API key entry modal appears in the UI |
| Key saved via UI before | Redis value is restored on restart — no prompt |

### Running

```bash
# Build
./gradlew build

# Run (embedded Redis starts automatically)
./gradlew bootRun

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.walter.spring.ai.ops.service.AiModelServiceTest"
```

Open `http://localhost:7079` in your browser. On first launch you will be prompted to enter your LLM API key (unless pre-configured in yml).

### Setting Up Grafana

1. In Grafana, go to **Alerting → Contact points → New contact point**.
2. Select type **Webhook**.
3. Set URL to `http://<your-host>:7079/webhook/grafana` (or `/webhook/grafana/{application}` to tag results with an application name).
4. Add the contact point to your alert rule's notification policy.

> Configure `loki.url` first. Loki is the primary data source for Grafana alert analysis; Prometheus only enriches the analysis when configured.

> Ensure Prometheus labels (`job`, `instance`, `namespace`, `pod`, etc.) and Loki stream labels are aligned so the same alert labels can drive both log and metric queries automatically.

> **Note**: Loki authentication (Basic Auth, Bearer Token, etc.) is not currently supported. Only unauthenticated Loki endpoints are supported at this time.

### Setting Up GitHub Webhooks

1. Go to **Repository → Settings → Webhooks → Add webhook**.
2. Set **Payload URL** to `http://<your-host>:7079/webhook/git/{application}`.
3. Set **Content type** to `application/json`.
4. Select event: **Just the push event**.
5. Save the webhook.

Ensure your GitHub personal access token (configured in yml or via the UI) has `repo` read scope (classic PAT) or `Contents: Read` permission (fine-grained PAT).

> **Note**: GitHub Webhook **Secret** is not currently supported. Leave the Secret field blank when configuring the webhook. Secret-based HMAC-SHA256 signature verification is planned for a future release.

### Setting Up GitLab Webhooks

1. Go to **Project → Settings → Webhooks → Add new webhook**.
2. Set **URL** to `http://<your-host>:7079/webhook/git/{application}`.
3. Under **Trigger**, check **Push events**.
4. Save the webhook.

Ensure your GitLab personal access token (configured in yml or via the UI) has `read_api` scope. For self-hosted GitLab instances, set `gitlab.url` to your instance's API base URL (e.g. `https://gitlab.example.com/api/v4`).

> **Note**: GitLab Webhook **Secret Token** is not currently supported. Leave the Secret Token field blank when configuring the webhook.

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Dashboard UI |
| `POST` | `/api/llm/config` | Save LLM provider + API key |
| `POST` | `/api/llm/select-provider` | Select provider when both yml keys are present |
| `GET` | `/api/loki/status` | Get Loki configuration status |
| `POST` | `/api/loki/config` | Save Loki base URL |
| `GET` | `/api/prometheus/status` | Get Prometheus configuration status |
| `POST` | `/api/prometheus/config` | Save Prometheus base URL (optional) |
| `POST` | `/api/github/config` | Save GitHub / GitLab access token and base URL |
| `GET` | `/api/github/config/status` | Get Git provider configuration status |
| `GET` | `/api/app/list` | List registered applications |
| `POST` | `/api/app/add` | Register a new application |
| `DELETE` | `/api/app/remove/{application}` | Remove an application |
| `GET` | `/api/firing/{application}/list` | Get alert analysis records for an application |
| `GET` | `/api/commit/{application}/list` | Get code review records for an application |
| `POST` | `/api/code-risk` | Run static code risk analysis for an application |
| `GET` | `/api/code-risk/{application}/list` | Get static analysis records for an application |
| `POST` | `/webhook/grafana[/{application}]` | Grafana Alerting webhook receiver |
| `POST` | `/webhook/git[/{application}]` | GitHub / GitLab push webhook receiver |

**WebSocket topics** (STOMP over SockJS at `/ws`)

| Topic | Payload | Triggered when |
|---|---|---|
| `/topic/firing` | `AnalyzeFiringRecord` | LLM error analysis completes |
| `/topic/commit` | `CodeReviewRecord` | LLM code review completes |
| `/topic/analysis/status` | `String` | Static analysis progress update (clone / chunk / consolidate) |
| `/topic/analysis/result` | `CodeRiskRecord` | Static analysis completes |

---

## API Documentation (Swagger)

Spring AI Ops integrates [springdoc-openapi](https://springdoc.org/) to provide interactive API documentation.

| URL | Description |
|---|---|
| `http://localhost:7079/swagger-ui.html` | Swagger UI — browse and try all REST endpoints |
| `http://localhost:7079/v3/api-docs` | OpenAPI 3.0 spec (JSON) |
| `http://localhost:7079/v3/api-docs.yaml` | OpenAPI 3.0 spec (YAML) |

The Swagger UI is useful for testing webhook payloads and configuration endpoints without an external tool.

---

## Package Structure

```
com.walter.spring.ai.ops
├── SpringAiOpsApplication.kt
├── code/
│   ├── AlertingStatus.kt          # FIRING / RESOLVED / ACCEPTED
│   ├── ConnectionStatus.kt        # SUCCESS / READY / FAILURE
│   ├── GitRemoteProvider.kt       # GITHUB / GITLAB enum
│   ├── LlmProvider.kt             # OPEN_AI / ANTHROPIC enum with product name & key
│   └── RedisKeyConstants.kt       # Centralised Redis key constants
├── config/
│   ├── CsrfTokenProvider.kt       # Generates a startup-time CSRF token for same-origin protection
│   ├── CsrfTokenInterceptor.kt    # Validates X-CSRF-Token header on /api/code-risk/**
│   ├── EmbeddedRedisConfig.kt     # Auto-start embedded Redis (local profile)
│   ├── GithubConnectorConfig.kt   # Feign client configuration for GitHub API
│   ├── GitlabConnectorConfig.kt   # Feign client configuration for GitLab API
│   ├── LokiConnectorConfig.kt     # Feign client configuration for Loki API
│   ├── PrometheusConnectorConfig.kt  # Feign client configuration for Prometheus API
│   ├── SwaggerConfig.kt           # springdoc-openapi OpenAPI info & server config
│   ├── VirtualThreadConfig.kt     # Virtual thread task executor
│   ├── WebMvcConfig.kt            # Registers CsrfTokenInterceptor on /api/code-risk/**
│   ├── WebSocketConfig.kt         # STOMP WebSocket endpoint & broker
│   ├── annotation/Facade.kt       # Custom @Facade stereotype annotation
│   └── base/DynamicConnectorConfig.kt  # Abstract base for dynamic URL resolution (GitHub / Loki)
├── connector/
│   ├── GithubConnector.kt         # Feign: GitHub Commits / Compare API
│   ├── GitlabConnector.kt         # Feign: GitLab Compare / Commit Diff API
│   ├── LokiConnector.kt           # Feign: Loki query_range API
│   ├── PrometheusConnector.kt     # Feign: Prometheus query_range API
│   └── dto/                       # Response DTOs (GithubCompareResult, LokiQueryResult, ...)
├── controller/
│   ├── IndexController.kt         # GET /
│   ├── WebhookController.kt       # POST /webhook/grafana, /webhook/git
│   ├── AiConfigController.kt      # POST /api/llm/*
│   ├── LokiConfigController.kt    # GET /api/loki/status, POST /api/loki/config, GET /api/prometheus/status, POST /api/prometheus/config
│   ├── GitRemoteConfigController.kt  # POST /api/github/config, GET /api/github/config/status
│   ├── ApplicationController.kt   # GET|POST|DELETE /api/app/*
│   ├── FiringController.kt        # GET /api/firing/{app}/list
│   ├── CommitController.kt        # GET /api/commit/{app}/list
│   ├── CodeRiskController.kt      # POST /api/code-risk, GET /api/code-risk/{app}/list
│   └── dto/                       # Request/Response DTOs
├── event/
│   ├── RateLimitHitEvent.kt       # Published by AiModelService on 429 response
│   └── RateLimitHitEventListener.kt  # Forwards rate-limit event to MessageService
├── facade/
│   ├── ObservabilityFacade.kt     # Orchestrates firing analysis & code review
│   └── CodeRiskFacade.kt          # Orchestrates static code risk analysis (clone → analyze → save)
├── record/
│   ├── AnalyzeFiringRecord.java   # Grafana analysis result (Java record)
│   ├── CodeReviewRecord.java      # Code review result (Java record)
│   ├── ChangedFile.java           # Per-file diff info (Java record)
│   ├── CodeRiskRecord.java        # Static analysis result (Java record)
│   ├── CodeRiskIssue.java         # Per-issue entry: file, line, severity, description, codeSnippet
│   └── CommitSummary.java         # Commit metadata: id, message, url, timestamp
├── service/
│   ├── AiModelService.kt          # ChatModel lifecycle & LLM calls
│   ├── ApplicationService.kt      # Application registry (Redis)
│   ├── GrafanaService.kt          # Alert → Loki inquiry, firing record persistence
│   ├── GitRemoteService.kt        # Abstract base for GitHub / GitLab services (token, URL, diff)
│   ├── GithubService.kt           # GitHub differ inquiry, code review persistence
│   ├── GitlabService.kt           # GitLab differ inquiry, code review persistence
│   ├── LokiService.kt             # Loki log query execution
│   ├── PrometheusService.kt       # Prometheus metric query execution
│   ├── MessageService.kt          # WebSocket push for all topics (firing, commit, analysis)
│   ├── RepositoryService.kt       # Git clone, source file collection, record persistence for code-risk
│   └── dto/CodeChunk.kt           # Bundle chunk for map-reduce analysis
└── util/
    ├── CodeAnalysisResultHandler.kt  # JSON parsing, sanitisation, and recovery for LLM issue output
    ├── CryptoProvider.kt          # AES encryption/decryption for stored API keys
    ├── RedisExtensions.kt         # zSetPushWithTtl / zSetRangeAllDesc helpers
    ├── StringExtentions.kt        # toISO8601 helper
    └── URIExtentions.kt           # URI builder helpers
```

---

## Changelog

| Date | Description |
|---|---|
| 2026-04-26 | Added Prometheus metric query to Grafana alert analysis — `PrometheusService` fetches `query_range` data alongside Loki logs and sends both to the LLM; Prometheus is optional (`prometheus.url` may be left blank) |
| 2026-04-22 | Added CSRF token same-origin protection for `/api/code-risk/**` — token embedded in HTML meta tag, validated via `X-CSRF-Token` header |
| 2026-04-22 | Added `<think>` block stripping in `AiModelService` to remove chain-of-thought output from models that emit it (e.g. DeepSeek, QwQ) |
| 2026-04-22 | Added fallback JSON parser in `CodeAnalysisResultHandler` to recover partial issue data when LLM returns malformed delimiters or truncated JSON |
| 2026-04-22 | Added `@Schema` annotations to all request/response DTOs and Java records for complete Swagger UI documentation |
| 2026-04-20 | Added Static Code Risk Analysis — clone a Git repository and run AI-powered full-codebase review with single-call or map-reduce strategy; results include per-issue severity, affected file, and code snippet |
| 2026-04-18 | Added GitLab push webhook support — automatically detected via `X-Gitlab-Event` header on the unified `/webhook/git` endpoint |
| 2026-04-15 | Abstracted external connector integration with a shared dynamic URL resolution base for GitHub and Loki |
| 2026-04-15 | Fixed embedded Redis startup failure on macOS ARM64 — requires `brew install openssl@3` due to dynamic link dependency in the bundled binary |
| 2026-04-15 | Replaced `@PostConstruct` with `@EventListener(ApplicationReadyEvent::class)` in `AiModelService` to prevent Redis connection attempts before embedded Redis has fully started |
| 2026-04-13 | Added Status column to Firing List — automatically extracts Exception/Error info from logs |

---

## License

This project is open source and available under the [MIT License](LICENSE).

```
MIT License

Copyright (c) 2025 Walter Hwang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---
