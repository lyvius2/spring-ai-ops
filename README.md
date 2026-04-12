# Spring AI Ops

An AI-powered operations automation tool that receives webhooks from **Grafana Alerting** and **GitHub**, then uses an LLM (OpenAI or Anthropic) to analyze errors and review code in real time — with results delivered to a live dashboard via WebSocket.

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Interface Flows](#interface-flows)
  - [Grafana → Loki → LLM](#grafana--loki--llm-error-analysis)
  - [GitHub → LLM](#github--llm-code-review)
- [Technology Stack](#technology-stack)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Configuration](#configuration)
  - [Running](#running)
  - [Setting Up Grafana](#setting-up-grafana)
  - [Setting Up GitHub Webhooks](#setting-up-github-webhooks)
- [API Reference](#api-reference)
- [API Documentation (Swagger)](#api-documentation-swagger)
- [Package Structure](#package-structure)
- [License](#license)
- [한국어 문서](#한국어-문서)

---

## Overview

Spring AI Ops bridges your monitoring and version-control toolchain with large language models. When Grafana fires an alert, the application automatically queries the corresponding Loki logs, feeds the alert context and log lines to an LLM, and streams a root-cause analysis to the dashboard. When a GitHub push webhook arrives, the application fetches the commit diff and sends it to the LLM for an automated code review. All results are pushed to connected browsers in real time via STOMP WebSocket.

No relational database is used. Redis serves as the sole persistence layer — storing LLM configuration, application registry, alert analysis records, and code review records.

---

## Key Features

| Feature | Description |
|---|---|
| **LLM-Powered Error Analysis** | Grafana alert context + Loki logs → root cause, affected components, and recommended actions |
| **Automated Code Review** | GitHub commit diff → code quality, potential bugs, security considerations |
| **Real-Time Dashboard** | WebSocket STOMP push to browser on analysis completion |
| **Dynamic LLM Configuration** | Switch between OpenAI and Anthropic at runtime via the UI — no restart required |
| **Multi-Application** | Register multiple application names; analysis history is scoped per application |
| **Zero-RDB Design** | Redis is the only data store; embedded Redis starts automatically in local dev |
| **Virtual Thread Executor** | Webhook handlers return immediately; analysis runs on Java 21 virtual threads |

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
│  ApplicationController    ├─ LokiService         ──► Loki API   │
│  FiringController         ├─ GithubConnector     ──► GitHub API │
│  CommitController         └─ AiModelService      ──► LLM API   │
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

### GitHub → LLM (Code Review)

```
git push to repository
        │
        ▼  (GitHub Webhook)
POST /webhook/github[/{application}]
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

## Technology Stack

| Category | Technology |
|---|---|
| Language | Kotlin 2.2 / Java 21 |
| Framework | Spring Boot 3.4.4 |
| AI | Spring AI 1.1.0 — OpenAI (`gpt-4o-mini`), Anthropic (`claude-3-5-sonnet-20241022`) |
| Persistence | Redis (primary store, no RDBMS) |
| Dev Redis | Embedded Redis (auto-start, no install needed) |
| HTTP Client | Spring Cloud OpenFeign + Resilience4j Circuit Breaker |
| Real-Time | Spring WebSocket (STOMP over SockJS) |
| Templating | Mustache |
| API Docs | springdoc-openapi 2.8.3 (Swagger UI) |
| Async | Java 21 Virtual Threads (`CompletableFuture` + `VirtualThreadTaskExecutor`) |
| Build | Gradle Kotlin DSL |

**Design note — Spring AI AutoConfiguration disabled**

All Spring AI `AutoConfiguration` classes are explicitly excluded in `application.yml`. `AiModelService` builds `OpenAiChatModel` / `AnthropicChatModel` directly using `ToolCallingManager.builder().build()`, `RetryUtils.DEFAULT_RETRY_TEMPLATE`, and `ObservationRegistry.NOOP`. This gives full control over model instantiation and allows hot-swapping the LLM provider at runtime.

---

## Getting Started

### Prerequisites

- JDK 21+
- (Optional) A running Loki instance if you want log queries
- An API key for at least one LLM provider (OpenAI or Anthropic)
- A GitHub personal access token if you want code review

### Configuration

Edit `src/main/resources/application.yml`:

```yaml
ai:
  open-ai:
    model: gpt-4o-mini                   # OpenAI model name
    api-key: ${AI_OPENAI_API_KEY:}       # Or set env var AI_OPENAI_API_KEY
  anthropic:
    model: claude-3-5-sonnet-20241022    # Anthropic model name
    api-key: ${AI_ANTHROPIC_API_KEY:}    # Or set env var AI_ANTHROPIC_API_KEY

loki:
  url: ${LOKI_URL:}                      # e.g. http://localhost:3100 (authentication is not supported)

github:
  url: ${GITHUB_URL:https://api.github.com}
  access-token: ${GITHUB_ACCESS_TOKEN:}  # GitHub personal access token

analysis:
  data-retention-hours: 120  # How long to keep analysis records (default: 5 days)
  maximum-view-count: 5      # Max records shown per application (0 = unlimited)
  result-language: en        # Language of LLM analysis output (e.g. ko, ja, en)
```

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

Open `http://localhost:8080` in your browser. On first launch you will be prompted to enter your LLM API key (unless pre-configured in yml).

### Setting Up Grafana

1. In Grafana, go to **Alerting → Contact points → New contact point**.
2. Select type **Webhook**.
3. Set URL to `http://<your-host>:8080/webhook/grafana` (or `/webhook/grafana/{application}` to tag results with an application name).
4. Add the contact point to your alert rule's notification policy.

> Ensure Prometheus labels (`job`, `instance`, etc.) and Loki stream labels are identical so log queries work automatically.

> **Note**: Loki authentication (Basic Auth, Bearer Token, etc.) is not currently supported. Only unauthenticated Loki endpoints are supported at this time.

### Setting Up GitHub Webhooks

1. Go to **Repository → Settings → Webhooks → Add webhook**.
2. Set **Payload URL** to `http://<your-host>:8080/webhook/github/{application}`.
3. Set **Content type** to `application/json`.
4. Select event: **Just the push event**.
5. Save the webhook.

Ensure your GitHub access token (configured in yml or via the UI) has `repo` read scope.

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Dashboard UI |
| `POST` | `/api/llm/config` | Save LLM provider + API key |
| `POST` | `/api/llm/select-provider` | Select provider when both yml keys are present |
| `POST` | `/api/loki/config` | Save Loki base URL |
| `POST` | `/api/github/token` | Save GitHub access token |
| `GET` | `/api/app/list` | List registered applications |
| `POST` | `/api/app/add` | Register a new application |
| `DELETE` | `/api/app/remove/{application}` | Remove an application |
| `GET` | `/api/firing/{application}/list` | Get alert analysis records for an application |
| `GET` | `/api/commit/{application}/list` | Get code review records for an application |
| `POST` | `/webhook/grafana[/{application}]` | Grafana Alerting webhook receiver |
| `POST` | `/webhook/github[/{application}]` | GitHub push webhook receiver |

**WebSocket topics** (STOMP over SockJS at `/ws`)

| Topic | Payload | Triggered when |
|---|---|---|
| `/topic/firing` | `AnalyzeFiringRecord` | LLM error analysis completes |
| `/topic/commit` | `CodeReviewRecord` | LLM code review completes |

---

## API Documentation (Swagger)

Spring AI Ops integrates [springdoc-openapi](https://springdoc.org/) to provide interactive API documentation.

| URL | Description |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Swagger UI — browse and try all REST endpoints |
| `http://localhost:8080/v3/api-docs` | OpenAPI 3.0 spec (JSON) |
| `http://localhost:8080/v3/api-docs.yaml` | OpenAPI 3.0 spec (YAML) |

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
│   ├── WebhookController.kt       # POST /webhook/grafana, /webhook/github
│   ├── AiConfigController.kt      # POST /api/llm/*
│   ├── LokiConfigController.kt    # POST /api/loki/config
│   ├── GithubConfigController.kt  # POST /api/github/token
│   ├── ApplicationController.kt   # GET|POST|DELETE /api/app/*
│   ├── FiringController.kt        # GET /api/firing/{app}/list
│   ├── CommitController.kt        # GET /api/commit/{app}/list
│   └── dto/                       # Request/Response DTOs
├── facade/
│   └── AnalyzeFacade.kt           # Orchestrates firing analysis & code review
├── record/
│   ├── AnalyzeFiringRecord.java   # Grafana analysis result (Java record)
│   ├── CodeReviewRecord.java      # Code review result (Java record)
│   └── ChangedFile.java           # Per-file diff info (Java record)
├── service/
│   ├── AiModelService.kt          # ChatModel lifecycle & LLM calls
│   ├── ApplicationService.kt      # Application registry (Redis)
│   ├── GrafanaService.kt          # Alert → Loki inquiry, firing record persistence
│   ├── GithubService.kt           # GitHub differ inquiry, code review persistence
│   └── LokiService.kt             # Loki log query execution
└── util/
    ├── RedisExtensions.kt         # listPushWithTtl helper
    ├── StringExtentions.kt        # toISO8601 helper
    └── URIExtentions.kt           # URI builder helpers
```

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

**Spring AI Ops**는 Grafana Alerting webhook과 GitHub push webhook을 수신하여, OpenAI 또는 Anthropic LLM으로 오류를 분석하고 코드 리뷰를 자동 수행하는 AI 기반 운영 자동화 도구입니다. 분석 결과는 WebSocket을 통해 실시간으로 대시보드에 전달됩니다.

관계형 데이터베이스는 사용하지 않으며, Redis를 유일한 저장소로 사용합니다. 로컬 개발 환경에서는 Embedded Redis가 자동으로 기동되므로 별도 설치가 필요 없습니다.

---

### 주요 기능

| 기능 | 설명 |
|---|---|
| **LLM 장애 분석** | Grafana 알림 컨텍스트 + Loki 로그 → 근본 원인, 영향 범위, 조치 방법 |
| **자동 코드 리뷰** | GitHub 커밋 diff → 코드 품질, 잠재적 버그, 보안 고려사항 |
| **실시간 대시보드** | 분석 완료 시 WebSocket STOMP으로 브라우저에 즉시 전달 |
| **동적 LLM 전환** | 재시작 없이 UI에서 OpenAI ↔ Anthropic 전환 |
| **다중 애플리케이션** | 여러 애플리케이션 등록 가능, 분석 히스토리가 애플리케이션별로 분리 |
| **RDB 미사용** | Redis만 사용, 로컬 개발 시 Embedded Redis 자동 기동 |
| **Virtual Thread** | 웹훅 핸들러는 즉시 응답, 분석은 Java 21 가상 스레드에서 비동기 처리 |

---

### 인터페이스 흐름

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

#### GitHub → LLM (코드 리뷰)

```
git push 발생
        │
        ▼  (GitHub Webhook)
POST /webhook/github[/{application}]
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

---

### 기술 스택

| 구분 | 기술 |
|---|---|
| 언어 | Kotlin 2.2 / Java 21 |
| 프레임워크 | Spring Boot 3.4.4 |
| AI | Spring AI 1.1.0 — OpenAI (`gpt-4o-mini`), Anthropic (`claude-3-5-sonnet-20241022`) |
| 저장소 | Redis (유일한 데이터 저장소, RDB 미사용) |
| 개발용 Redis | Embedded Redis (자동 기동, 별도 설치 불필요) |
| HTTP 클라이언트 | Spring Cloud OpenFeign + Resilience4j Circuit Breaker |
| 실시간 통신 | Spring WebSocket (STOMP over SockJS) |
| 템플릿 | Mustache |
| API 문서 | springdoc-openapi 2.8.3 (Swagger UI) |
| 비동기 | Java 21 Virtual Thread (`CompletableFuture` + `VirtualThreadTaskExecutor`) |
| 빌드 | Gradle Kotlin DSL |

---

### 시작하기

#### 사전 요구사항

- JDK 21 이상
- OpenAI 또는 Anthropic API 키 (둘 중 하나 이상)
- 코드 리뷰 기능 사용 시 GitHub Personal Access Token
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
  access-token: ${GITHUB_ACCESS_TOKEN:}  # GitHub 액세스 토큰

analysis:
  data-retention-hours: 120  # 분석 결과 보관 시간 (기본: 5일)
  maximum-view-count: 5      # 애플리케이션별 최대 표시 건수 (0 = 무제한)
  result-language: ko        # LLM 분석 결과 언어 (ko, en, ja 등)
```

#### 실행

```bash
# 빌드
./gradlew build

# 실행 (Embedded Redis 자동 기동)
./gradlew bootRun

# 전체 테스트
./gradlew test
```

브라우저에서 `http://localhost:8080`에 접속합니다. yml에 API 키가 설정되어 있으면 자동으로 LLM이 구성됩니다.

#### Grafana 설정

1. **Alerting → Contact points → New contact point**
2. 유형: **Webhook**
3. URL: `http://<your-host>:8080/webhook/grafana/{application}`
4. 알림 정책에 연결

> **주의**: 현재 Loki 인증(Basic Auth, Bearer Token 등)은 지원하지 않습니다. 인증 없이 접근 가능한 Loki 엔드포인트만 사용할 수 있습니다.

#### GitHub Webhook 설정

1. **Repository → Settings → Webhooks → Add webhook**
2. Payload URL: `http://<your-host>:8080/webhook/github/{application}`
3. Content type: `application/json`
4. 이벤트: **Just the push event**

### API 문서 (Swagger)

[springdoc-openapi](https://springdoc.org/)를 통해 Swagger UI를 제공합니다. 애플리케이션 실행 후 아래 URL에서 확인할 수 있습니다.

| URL | 설명 |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Swagger UI — 모든 REST 엔드포인트를 브라우저에서 직접 테스트 가능 |
| `http://localhost:8080/v3/api-docs` | OpenAPI 3.0 명세 (JSON) |
| `http://localhost:8080/v3/api-docs.yaml` | OpenAPI 3.0 명세 (YAML) |

webhook 페이로드나 설정 엔드포인트를 별도 도구 없이 Swagger UI에서 바로 테스트할 수 있습니다.

---

### 라이선스

이 프로젝트는 [MIT License](LICENSE) 하에 오픈소스로 공개되어 있습니다.
