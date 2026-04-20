# Spring AI Ops

An AI-powered operations automation tool that receives webhooks from **Grafana Alerting**, **GitHub**, and **GitLab**, then uses an LLM (OpenAI or Anthropic) to analyze errors, review code, and perform static code risk analysis in real time — with results delivered to a live dashboard via WebSocket.

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Interface Flows](#interface-flows)
  - [Static Code Risk Analysis](#static-code-risk-analysis)
  - [GitHub → LLM](#github--llm-code-review)
  - [GitLab → LLM](#gitlab--llm-code-review)
  - [Grafana → Loki → LLM](#grafana--loki--llm-error-analysis)
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
- [한국어 문서](#한국어-문서)

---

## Overview

Spring AI Ops bridges your monitoring and version-control toolchain with large language models. When Grafana fires an alert, the application automatically queries the corresponding Loki logs, feeds the alert context and log lines to an LLM, and streams a root-cause analysis to the dashboard. When a GitHub or GitLab push webhook arrives, the application fetches the commit diff and sends it to the LLM for an automated code review. All results are pushed to connected browsers in real time via STOMP WebSocket.

No relational database is used. Redis serves as the sole persistence layer — storing LLM configuration, application registry, alert analysis records, and code review records.

> **Live Demo**: [https://ai-ops.duckdns.org](https://ai-ops.duckdns.org)

---

## Key Features

| Feature | Description |
|---|---|
| **Static Code Risk Analysis** | Clone a Git repository and run an AI-powered full-codebase review — security vulnerabilities, code quality issues, and actionable recommendations. Supports single-call and map-reduce strategies based on codebase size |
| **Automated Code Review** | GitHub / GitLab commit diff → code quality, potential bugs, security considerations |
| **LLM-Powered Error Analysis** | Grafana alert context + Loki logs → root cause, affected components, and recommended actions |
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
│  AiConfigController       ├─ GrafanaService      ──► Redis      │
│  LokiConfigController     ├─ GithubService       ──► Redis      │
│  ApplicationController    ├─ GitlabService       ──► Redis      │
│  FiringController         ├─ LokiService         ──► Loki API   │
│  CommitController         ├─ GithubConnector     ──► GitHub API │
│                           ├─ GitlabConnector     ──► GitLab API │
│                           └─ AiModelService      ──► LLM API   │
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
- **Connector** — OpenFeign clients for Loki and GitHub REST APIs.

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

### Grafana → Loki → LLM (Error Analysis)

```
Grafana Alert fires
        │
        ▼
POST /webhook/grafana[/{application}]
        │
        ├─ status == "resolved"? → skip (return RESOLVED)
        │
        ├─ Extract Loki stream selector from alert labels
        │    e.g. {job="my-app", namespace="prod", pod="api-xyz"}
        │
        ├─ Calculate time range
        │    start = alert.startsAt − 5 min buffer
        │    end   = alert.endsAt  (current time if zero-value)
        │
        ├─ Query Loki
        │    GET {loki.url}/loki/api/v1/query_range
        │    ?query={...}&start=...&end=...
        │
        ├─ Call LLM
        │    System: expert in application errors and logs
        │    User:   alert context + log lines
        │            → root cause / affected components / recommended actions
        │
        ├─ Save AnalyzeFiringRecord to Redis  (key: firing:{application})
        │
        └─ Push to /topic/firing via WebSocket
                │
                ▼
           Browser receives analysis result in real time
```

> **Prerequisite**: Prometheus metric labels and Loki stream labels must share the same key set (`job`, `instance`, `namespace`, `pod`, etc.). Configure Promtail or Grafana Alloy accordingly.

---

## Screenshots

### LLM API Key Configuration
*Enter your LLM provider and API key through the UI. The model is activated immediately without restarting the application.*  
*LLM 제공자와 API 키를 UI에서 입력합니다. 애플리케이션 재시작 없이 즉시 모델이 활성화됩니다.*

![LLM API Key Configuration](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/AIConfig.png?raw=true)

---

### Static Code Risk Analysis
*Run a full AI-powered static analysis on any registered Git repository. Issues are grouped by file with severity levels (HIGH / MEDIUM / LOW), and each entry includes the affected code snippet and a recommended fix.*  
*등록된 Git 저장소를 대상으로 AI 기반 전체 코드 정적 분석을 실행합니다. 이슈는 파일 단위로 그룹화되어 심각도(HIGH / MEDIUM / LOW)와 함께 표시되며, 각 항목에는 문제 코드 스니펫과 개선 권고사항이 포함됩니다.*

![Static Code Risk Analysis](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/CodeRisk.png?raw=true)

---

### AI-Powered Code Review
*When a GitHub push event is received, the LLM reviews the commit diff per changed file and delivers a structured report — covering code quality, potential bugs, security considerations, and improvement suggestions.*  
*GitHub push 이벤트가 수신되면 LLM이 변경 파일별 diff를 리뷰하여 코드 품질, 잠재적 버그, 보안 고려사항, 개선 제안을 구조화된 보고서로 제공합니다.*

![Code Review](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/CodeReview.png?raw=true)

---

### Grafana Alerting Webhook
*When a Grafana alert fires, the webhook payload is delivered to Spring AI Ops in real time. The alert status and labels are visible in the dashboard.*  
*Grafana 알림이 발생하면 webhook 페이로드가 실시간으로 Spring AI Ops에 전달됩니다. 대시보드에서 알림 상태와 레이블을 확인할 수 있습니다.*

![Grafana Alerting Webhook](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/GrafanaAlerting.png?raw=true)

---

### AI-Powered Error Analysis
*The LLM analyzes the Grafana alert context along with the corresponding Loki logs and streams a root-cause analysis — including affected components and recommended actions — directly to the dashboard.*  
*LLM이 Grafana 알림 컨텍스트와 Loki 로그를 함께 분석하여 근본 원인, 영향 범위, 조치 방법을 대시보드에 실시간으로 스트리밍합니다.*

![Firing Analysis](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/FiringAnalyze.png?raw=true)

---

## Technology Stack

| Category | Technology |
|---|---|
| Language | Kotlin 2.2 / Java 21 |
| Framework | Spring Boot 3.4.4 |
| AI | Spring AI 1.1.0 — OpenAI (`gpt-4o-mini`), Anthropic (`claude-sonnet-4-6`) |
| Persistence | Redis (primary store, no RDBMS) |
| Dev Redis | Embedded Redis (auto-start, no install needed) |
| HTTP Client | Spring Cloud OpenFeign + Resilience4j Circuit Breaker |
| Real-Time | Spring WebSocket (STOMP over SockJS) |
| Templating | Mustache |
| API Docs | springdoc-openapi 2.8.3 (Swagger UI) |
| Async | Java 21 Virtual Threads (`CompletableFuture` + unlimited `SimpleAsyncTaskExecutor`) + Semaphore-based LLM rate limiter |
| Build | Gradle Kotlin DSL |

**Design note — Spring AI AutoConfiguration disabled**

All Spring AI `AutoConfiguration` classes are explicitly excluded in `application.yml`. `AiModelService` builds `OpenAiChatModel` / `AnthropicChatModel` directly using `ToolCallingManager.builder().build()`, `RetryUtils.DEFAULT_RETRY_TEMPLATE`, and `ObservationRegistry.NOOP`. This gives full control over model instantiation and allows hot-swapping the LLM provider at runtime.

**Design note — Virtual Thread concurrency**

The `SimpleAsyncTaskExecutor` runs with **no concurrency limit** (`-1`). Virtual Threads release their OS carrier thread on blocking I/O, so an artificial cap would only trigger `ConcurrencyThrottledException` without providing any backpressure benefit. Instead, a `Semaphore` (`app.async.virtual.llm-max-concurrency`, default `10`) guards only the actual LLM API call inside `AiModelService`. Excess requests wait in a fair queue rather than failing, and the virtual-thread executor itself remains unblocked.

**Design note — Resilience4j TimeLimiter + Virtual Thread compatibility**

Resilience4j's `TimeLimiter` cancels timed-out tasks via `future.cancel(true)`, which calls `Thread.interrupt()`. Virtual Threads handle interrupts differently from platform threads — particularly when pinned to a carrier thread — so the interrupt may not propagate correctly, leaving tasks running past the timeout silently.

To avoid this, `resilience4j.timelimiter.configs.default.cancel-running-future` is set to `false`. This disables the interrupt-based cancellation. Actual I/O timeouts are enforced instead by Feign's own `Request.Options` (`feign.loki.*` / `feign.github.*`), which operate at the socket level and are not affected by the Virtual Thread interrupt issue. The Circuit Breaker state machine (open/half-open/closed) and `FallbackFactory` remain fully active.

---

## Getting Started

### Prerequisites

- JDK 21+
- (Optional) A running Loki instance if you want log queries
- An API key for at least one LLM provider (OpenAI or Anthropic)
- A GitHub or GitLab personal access token if you want code review

### Configuration

Edit `src/main/resources/application.yml`:

```yaml
ai:
  open-ai:
    model: gpt-4o-mini                   # OpenAI model name
    api-key: ${AI_OPENAI_API_KEY:}       # Or set env var AI_OPENAI_API_KEY
  anthropic:
    model: claude-sonnet-4-6             # Anthropic model name
    api-key: ${AI_ANTHROPIC_API_KEY:}    # Or set env var AI_ANTHROPIC_API_KEY

loki:
  url: ${LOKI_URL:}                      # e.g. http://localhost:3100 (authentication is not supported)

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
  result-language: en        # Language of LLM analysis output (e.g. ko, ja, en)
  code-risk:
    token-threshold: 27000        # Max tokens for single-call analysis; larger bundles switch to map-reduce (default: 27000)
    map-reduce-concurrency: 3     # Max parallel chunk analysis calls in map phase (default: 3)
    map-reduce-delay-ms: 1000     # Delay (ms) after each chunk call in map phase (default: 1000)

app:
  async:
    virtual:
      llm-max-concurrency: 10  # Max simultaneous in-flight LLM API calls (Semaphore). Virtual thread executor itself is unlimited.

feign:
  loki:
    connect-timeout: 5000   # Loki connect timeout (ms)
    read-timeout: 30000     # Loki read timeout (ms)
  github:
    connect-timeout: 5000   # GitHub API connect timeout (ms)
    read-timeout: 30000     # GitHub API read timeout (ms)
  gitlab:
    connect-timeout: 5000   # GitLab API connect timeout (ms)
    read-timeout: 30000     # GitLab API read timeout (ms)
```

If both a property value and a Redis value exist for the same setting, the Redis value takes precedence.

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

> Ensure Prometheus labels (`job`, `instance`, etc.) and Loki stream labels are identical so log queries work automatically.

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
| `POST` | `/api/loki/config` | Save Loki base URL |
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
| `/topic/analysis/status` | `String` | Static analysis progress update |
| `/topic/analysis/result` | `String` | Static analysis completes (completion notification) |

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
│   └── ConnectionStatus.kt        # SUCCESS / READY / FAILURE
├── config/
│   ├── EmbeddedRedisConfig.kt     # Auto-start embedded Redis (local profile)
│   ├── GithubConnectorConfig.kt   # Feign client configuration for GitHub API
│   ├── LokiConnectorConfig.kt     # Feign client configuration for Loki API
│   ├── VirtualThreadConfig.kt     # Virtual thread task executor
│   ├── WebSocketConfig.kt         # STOMP WebSocket endpoint & broker
│   └── annotation/Facade.kt       # Custom @Facade stereotype annotation
├── connector/
│   ├── GithubConnector.kt         # Feign: GitHub Commits / Compare API
│   ├── LokiConnector.kt           # Feign: Loki query_range API
│   └── dto/                       # Response DTOs (GithubCompareResult, LokiQueryResult, ...)
├── controller/
│   ├── IndexController.kt         # GET /
│   ├── WebhookController.kt       # POST /webhook/grafana, /webhook/git
│   ├── AiConfigController.kt      # POST /api/llm/*
│   ├── LokiConfigController.kt    # POST /api/loki/config
│   ├── GithubConfigController.kt  # POST /api/github/token
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
│   └── CodeRiskIssue.java         # Per-issue entry: file, line, severity, description, codeSnippet
├── service/
│   ├── AiModelService.kt          # ChatModel lifecycle & LLM calls
│   ├── ApplicationService.kt      # Application registry (Redis)
│   ├── GrafanaService.kt          # Alert → Loki inquiry, firing record persistence
│   ├── GithubService.kt           # GitHub differ inquiry, code review persistence
│   ├── LokiService.kt             # Loki log query execution
│   ├── MessageService.kt          # WebSocket push for all topics (firing, commit, analysis)
│   ├── RepositoryService.kt       # Git clone, source file collection, record persistence for code-risk
│   └── dto/CodeChunk.kt           # Bundle chunk for map-reduce analysis
└── util/
    ├── RedisExtensions.kt         # listPushWithTtl helper
    ├── StringExtentions.kt        # toISO8601 helper
    └── URIExtentions.kt           # URI builder helpers
```

---

## Changelog

| Date | Description |
|---|---|
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

## 한국어 문서

### 프로젝트 개요

**Spring AI Ops**는 Grafana Alerting webhook과 GitHub / GitLab push webhook을 수신하여, OpenAI 또는 Anthropic LLM으로 오류를 분석하고 코드 리뷰를 자동 수행하는 AI 기반 운영 자동화 도구입니다. 분석 결과는 WebSocket을 통해 실시간으로 대시보드에 전달됩니다.

관계형 데이터베이스는 사용하지 않으며, Redis를 유일한 저장소로 사용합니다. 로컬 개발 환경에서는 Embedded Redis가 자동으로 기동되므로 별도 설치가 필요 없습니다.

> **데모 사이트**: [https://ai-ops.duckdns.org](https://ai-ops.duckdns.org)

---

### 주요 기능

| 기능 | 설명 |
|---|---|
| **정적 코드 위험 분석** | Git 저장소를 클론하여 AI 기반 전체 코드베이스 리뷰 수행 — 보안 취약점, 코드 품질 이슈, 개선 권고사항. 코드베이스 크기에 따라 단일 호출 또는 맵-리듀스 전략 자동 선택 |
| **자동 코드 리뷰** | GitHub / GitLab 커밋 diff → 코드 품질, 잠재적 버그, 보안 고려사항 |
| **LLM 장애 분석** | Grafana 알림 컨텍스트 + Loki 로그 → 근본 원인, 영향 범위, 조치 방법 |
| **실시간 대시보드** | 분석 완료 시 WebSocket STOMP으로 브라우저에 즉시 전달 |
| **동적 LLM 전환** | 재시작 없이 UI에서 OpenAI ↔ Anthropic 전환 |
| **다중 애플리케이션** | 여러 애플리케이션 등록 가능, 분석 히스토리가 애플리케이션별로 분리 |
| **RDB 미사용** | Redis만 사용, 로컬 개발 시 Embedded Redis 자동 기동 |
| **Virtual Thread** | 웹훅 핸들러는 즉시 응답, 분석은 Java 21 가상 스레드에서 비동기 처리. LLM API 호출은 별도 Semaphore로 동시 호출 수 제한 (기본: 20) |

---

### 인터페이스 흐름

#### 정적 코드 위험 분석

```
대시보드에서 "Run Static Analysis" 클릭
        │
        ▼
POST /api/code-risk
        │
        ├─ 애플리케이션에 등록된 Git 저장소 URL 조회
        │
        ├─ 액세스 토큰 결정 (Redis에 저장된 GitHub 또는 GitLab 토큰)
        │
        ├─ JGit으로 저장소 클론 (토큰 인증 적용)
        │    지정된 브랜치, 미입력 시 기본 브랜치
        │
        ├─ 소스 파일 수집 및 코드 번들 구성
        │
        ├─ 토큰 수 추정
        │    ≤ token-threshold (기본: 27,000)
        │      → 단일 호출 분석 (전체 번들을 LLM에 한 번에 전달)
        │    > token-threshold
        │      → 맵-리듀스 분석
        │           청크 분할 → 병렬 분석 (최대 동시 3개, 호출 간 1,000ms 지연)
        │           최종 LLM 호출로 청크 결과 통합
        │
        ├─ LLM 응답 파싱
        │    Markdown 분석  (전체 요약, 권고사항)
        │    Issues JSON   (파일, 라인, 심각도, 설명, 코드 스니펫)
        │
        ├─ CodeRiskRecord를 Redis에 저장 (key: code-risk:{application})
        │
        ├─ /topic/analysis/status 로 진행 상황 실시간 Push
        │
        └─ /topic/analysis/result 로 완료 알림 Push
                │
                ▼
           브라우저 Code Risk 탭에 전체 분석 결과 표시
```

> **참고**: 클론, 청크 분석, 통합 등 분석 진행 상황이 WebSocket을 통해 대시보드에 실시간으로 전달됩니다. LLM이 분석 도중 429(Rate Limit) 오류를 반환하면 중단하고 그 시점까지 수집된 결과를 저장합니다.

> **Git 인증**: Git Remote Configuration에서 등록한 GitHub 또는 GitLab 액세스 토큰이 비공개 저장소 클론에 자동으로 사용됩니다.

#### GitHub → LLM (코드 리뷰)

```
git push 발생
        │
        ▼  (GitHub Webhook)
POST /webhook/git[/{application}]
        │
        ├─ owner / repo / before SHA / after SHA 추출
        │
        ├─ GitHub API로 커밋 diff 조회
        │    before == 0000... (첫 push) → GET /commits/{sha}
        │    그 외                        → GET /compare/{base}...{head}
        │
        ├─ LLM 코드 리뷰 요청
        │    변경 파일별 diff
        │    → 변경 요약 / 잠재 이슈 / 보안 / 개선 제안
        │
        ├─ CodeReviewRecord를 Redis에 저장 (key: commit:{application})
        │
        └─ WebSocket /topic/commit 으로 결과 Push
```

#### GitLab → LLM (코드 리뷰)

```
git push 발생
        │
        ▼  (GitLab Webhook)
POST /webhook/git[/{application}]
        │
        ├─ X-Gitlab-Event 헤더 감지 → GitLab 페이로드로 파싱
        │
        ├─ project / before SHA / after SHA 추출
        │
        ├─ GitLab API로 커밋 diff 조회
        │    before == 0000... (첫 push) → GET /projects/{id}/repository/commits/{sha}/diff
        │    그 외                        → GET /projects/{id}/repository/compare?from={base}&to={head}
        │
        ├─ LLM 코드 리뷰 요청
        │    변경 파일별 diff
        │    → 변경 요약 / 잠재 이슈 / 보안 / 개선 제안
        │
        ├─ CodeReviewRecord를 Redis에 저장 (key: commit:{application})
        │
        └─ WebSocket /topic/commit 으로 결과 Push
```

#### Grafana → Loki → LLM (장애 분석)

```
Grafana Alert 발생
        │
        ▼
POST /webhook/grafana[/{application}]
        │
        ├─ status == "resolved"? → 처리 스킵 (RESOLVED 반환)
        │
        ├─ 알림 레이블로 Loki 스트림 셀렉터 생성
        │    예: {job="my-app", namespace="prod", pod="api-xyz"}
        │
        ├─ 시간 범위 계산
        │    start = alert.startsAt − 5분 버퍼
        │    end   = alert.endsAt  (zero-value이면 현재 시각)
        │
        ├─ Loki 로그 조회
        │    GET {loki.url}/loki/api/v1/query_range
        │
        ├─ LLM 분석 요청
        │    알림 컨텍스트 + 로그 라인
        │    → 근본 원인 / 영향 범위 / 조치 방법
        │
        ├─ AnalyzeFiringRecord를 Redis에 저장 (key: firing:{application})
        │
        └─ WebSocket /topic/firing 으로 결과 Push
```

> **전제 조건**: Prometheus 메트릭 레이블과 Loki 스트림 레이블이 동일(`job`, `instance` 등)해야 로그 조회가 동작합니다. Promtail 또는 Grafana Alloy 설정을 확인하세요.

---

### 기술 스택

| 구분 | 기술 |
|---|---|
| 언어 | Kotlin 2.2 / Java 21 |
| 프레임워크 | Spring Boot 3.4.4 |
| AI | Spring AI 1.1.0 — OpenAI (`gpt-4o-mini`), Anthropic (`claude-sonnet-4-6`) |
| 저장소 | Redis (유일한 데이터 저장소, RDB 미사용) |
| 개발용 Redis | Embedded Redis (자동 기동, 별도 설치 불필요) |
| HTTP 클라이언트 | Spring Cloud OpenFeign + Resilience4j Circuit Breaker |
| 실시간 통신 | Spring WebSocket (STOMP over SockJS) |
| 템플릿 | Mustache |
| API 문서 | springdoc-openapi 2.8.3 (Swagger UI) |
| 비동기 | Java 21 Virtual Thread (무제한 `SimpleAsyncTaskExecutor`) + Semaphore 기반 LLM 호출 수 제한 |
| 빌드 | Gradle Kotlin DSL |

---

### 시작하기

#### 사전 요구사항

- JDK 21 이상
- OpenAI 또는 Anthropic API 키 (둘 중 하나 이상)
- 코드 리뷰 기능 사용 시 GitHub 또는 GitLab Personal Access Token
- Loki 연동 시 Loki 서버 URL

#### 설정

`src/main/resources/application.yml`을 편집하거나 환경 변수를 설정합니다:

```yaml
ai:
  open-ai:
    api-key: ${AI_OPENAI_API_KEY:}       # OpenAI API 키
  anthropic:
    api-key: ${AI_ANTHROPIC_API_KEY:}    # Anthropic API 키

loki:
  url: ${LOKI_URL:}                      # Loki 서버 주소 (예: http://localhost:3100) — 인증 미지원

github:
  url: ${GITHUB_URL:https://api.github.com}      # GitHub API URL
  access-token: ${GITHUB_ACCESS_TOKEN:}          # GitHub 액세스 토큰
  api-version: ${GITHUB_API_VERSION:2022-11-28}  # GitHub API 버전 헤더

gitlab:
  url: ${GITLAB_URL:https://gitlab.com/api/v4}  # GitLab API URL (셀프 호스팅 시 인스턴스 주소로 변경)
  access-token: ${GITLAB_ACCESS_TOKEN:}          # GitLab 액세스 토큰

analysis:
  data-retention-hours: 120  # 분석 결과 보관 시간 (기본: 5일)
  maximum-view-count: 5      # 애플리케이션별 최대 표시 건수 (0 = 무제한)
  result-language: en        # LLM 분석 결과 언어 (ko, en, ja 등)
  code-risk:
    token-threshold: 27000        # 단일 호출 분석의 최대 토큰 수; 초과 시 맵-리듀스로 전환 (기본: 27000)
    map-reduce-concurrency: 3     # 맵 단계 병렬 청크 분석 최대 동시 수 (기본: 3)
    map-reduce-delay-ms: 1000     # 맵 단계 청크 호출 후 지연 시간 ms (기본: 1000)

app:
  async:
    virtual:
      llm-max-concurrency: 10  # 동시 LLM API 호출 허용 수 (Semaphore). Virtual Thread Executor 자체는 무제한.

feign:
  loki:
    connect-timeout: 5000   # Loki 연결 타임아웃 (ms)
    read-timeout: 30000     # Loki 읽기 타임아웃 (ms)
  github:
    connect-timeout: 5000   # GitHub API 연결 타임아웃 (ms)
    read-timeout: 30000     # GitHub API 읽기 타임아웃 (ms)
  gitlab:
    connect-timeout: 5000   # GitLab API 연결 타임아웃 (ms)
    read-timeout: 30000     # GitLab API 읽기 타임아웃 (ms)
```

동일한 설정에 대해 property 값과 Redis 값이 모두 있으면 Redis 값이 우선 적용됩니다.

#### 민감 정보 암호화

Redis에 저장되는 API 키와 액세스 토큰은 **AES-256-GCM** 방식으로 암호화되어 보관됩니다.

암호화를 활성화하려면 환경 변수 또는 `application.yml`에 시크릿 키를 설정합니다:

```bash
# 환경 변수 (운영 환경 권장)
export CRYPTO_SECRET_KEY=your-strong-secret-passphrase
```

```yaml
# application.yml
crypto:
  secret-key: ${CRYPTO_SECRET_KEY:}
```

| 상황 | 동작 |
|---|---|
| `crypto.secret-key` 설정됨 | Redis에 저장되는 모든 민감 값이 AES-256-GCM으로 암호화됨 |
| `crypto.secret-key` 미설정 | 값이 평문으로 저장됨 — 애플리케이션 기동 시 경고 로그 출력 |
| 키를 변경한 경우 | 기존 암호화 값 복호화 불가 — UI에서 API 키를 재입력하면 새 키로 재암호화됨 |

> **운영 환경 권고사항**: 반드시 `CRYPTO_SECRET_KEY`를 설정하세요. 미설정 시 Redis에 저장된 API 키가 평문으로 보관됩니다.

#### 실행

```bash
# 빌드
./gradlew build

# 실행 (Embedded Redis 자동 기동)
./gradlew bootRun

# 전체 테스트
./gradlew test
```

브라우저에서 `http://localhost:7079`에 접속합니다. yml에 API 키가 설정되어 있으면 자동으로 LLM이 구성됩니다.

#### Grafana 설정

1. **Alerting → Contact points → New contact point**
2. 유형: **Webhook**
3. URL: `http://<your-host>:7079/webhook/grafana/{application}`
4. 알림 정책에 연결

> **주의**: 현재 Loki 인증(Basic Auth, Bearer Token 등)은 지원하지 않습니다. 인증 없이 접근 가능한 Loki 엔드포인트만 사용할 수 있습니다.

#### GitHub Webhook 설정

1. **Repository → Settings → Webhooks → Add webhook**
2. Payload URL: `http://<your-host>:7079/webhook/git/{application}`
3. Content type: `application/json`
4. 이벤트: **Just the push event**

GitHub 액세스 토큰(yml 또는 UI에서 설정)은 `repo` read 스코프(클래식 PAT) 또는 `Contents: Read` 권한(세분화된 PAT)이 필요합니다.

> **참고**: GitHub Webhook **Secret**은 현재 지원하지 않습니다. Webhook 설정 시 Secret 필드는 비워두세요. Secret 기반 HMAC-SHA256 서명 검증은 추후 지원 예정입니다.

#### GitLab Webhook 설정

1. **Project → Settings → Webhooks → Add new webhook**
2. URL: `http://<your-host>:7079/webhook/git/{application}`
3. **Trigger** 항목에서 **Push events** 선택
4. 저장

GitLab 액세스 토큰(yml 또는 UI에서 설정)은 `read_api` 스코프가 필요합니다. 셀프 호스팅 GitLab 인스턴스를 사용하는 경우 `gitlab.url`을 해당 인스턴스의 API 기본 URL(예: `https://gitlab.example.com/api/v4`)로 설정하세요.

> **참고**: GitLab Webhook **Secret Token**은 현재 지원하지 않습니다. Webhook 설정 시 Secret Token 필드는 비워두세요.

### API 문서 (Swagger)

[springdoc-openapi](https://springdoc.org/)를 통해 Swagger UI를 제공합니다. 애플리케이션 실행 후 아래 URL에서 확인할 수 있습니다.

| URL | 설명 |
|---|---|
| `http://localhost:7079/swagger-ui.html` | Swagger UI — 모든 REST 엔드포인트를 브라우저에서 직접 테스트 가능 |
| `http://localhost:7079/v3/api-docs` | OpenAPI 3.0 명세 (JSON) |
| `http://localhost:7079/v3/api-docs.yaml` | OpenAPI 3.0 명세 (YAML) |

webhook 페이로드나 설정 엔드포인트를 별도 도구 없이 Swagger UI에서 바로 테스트할 수 있습니다.

---

### 라이선스

이 프로젝트는 [MIT License](LICENSE) 하에 오픈소스로 공개되어 있습니다.
