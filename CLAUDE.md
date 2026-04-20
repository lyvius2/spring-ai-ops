# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build
./gradlew build

# Run (embedded Redis starts automatically)
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.walter.spring.ai.ops.SomeTest"

# Compile only
./gradlew compileKotlin
```

## Architecture Overview

A Spring Boot application that receives Grafana Alerting and Git (GitHub/GitLab) webhooks and performs error analysis and code review using OpenAI or Anthropic LLMs.

**Tech stack**: Spring Boot 3.4.4 · Kotlin · Spring AI 1.1.0 · Redis · Mustache

## Key Design Decisions

### Spring AI AutoConfiguration Fully Disabled
All Spring AI AutoConfiguration classes are explicitly listed under `spring.autoconfigure.exclude` in `application.yml`. LLM clients are instantiated directly in `AiModelService` without auto-configuration.

### Dynamic ChatModel Creation and Persistence
- User selects LLM provider (openai/anthropic) and enters an API key via the UI (`GET /`)
- `POST /api/llm/config` → saved to Redis under `llm` and `llmKey` keys → `AiModelService` creates a `ChatModel` instance and holds it in a `@Volatile` field
- On restart, `@EventListener(ApplicationStartedEvent)` reads Redis values and restores the `ChatModel` automatically
- OpenAI: `gpt-4o-mini` / Anthropic: `claude-3-5-sonnet-20241022` (fixed)

### ChatModel Construction
Because AutoConfiguration is disabled, `ToolCallingManager.builder().build()`, `RetryUtils.DEFAULT_RETRY_TEMPLATE`, and `ObservationRegistry.NOOP` are composed manually.

### Embedded Redis for Local Development
`EmbeddedRedisConfig` starts a Redis server in `@PostConstruct` and shuts it down in `@PreDestroy`. The port follows `spring.data.redis.port` in `application.yml`.

## Package Structure

```
com.walter.spring.ai.ops
├── code/
│   ├── ConnectionStatus        # LLM connection result enum (SUCCESS, READY, FAILURE)
│   └── AlertingStatus          # Grafana webhook processing result enum (FIRING, RESOLVED)
├── config/
│   └── EmbeddedRedisConfig     # Embedded Redis lifecycle for local dev
├── controller/
│   ├── IndexController         # GET /  → renders index.mustache
│   ├── AiConfigController      # POST /api/llm/config
│   ├── WebhookController       # POST /webhook/grafana, POST /webhook/git/{app}
│   └── dto/
│       ├── AiConfigRequest / AiConfigResponse
│       ├── GrafanaAlertingRequest   # Top-level Grafana webhook payload
│       ├── GrafanaAlert             # Individual alert object (lokiLabels, lokiStartNano, lokiEndNano)
│       └── GrafanaAlertingResponse
├── facade/
│   ├── AnalyzeFacade           # Orchestrates firing analysis and code review workflows
│   └── GitRemoteFacade         # Orchestrates Git remote provider configuration
└── service/
    ├── AiModelService          # ChatModel lifecycle management
    ├── GithubService           # GitHub API integration
    ├── GitlabService           # GitLab API integration
    ├── GrafanaService          # Grafana alert record management
    └── LokiService             # Loki log query execution
```

## API Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/` | LLM configuration UI |
| POST | `/api/llm/config` | Save LLM provider and API key, create ChatModel |
| POST | `/webhook/grafana/{app}` | Receive Grafana Alerting webhook |
| POST | `/webhook/git/{app}` | Receive GitHub/GitLab push webhook |

## Grafana → Loki → LLM Flow

`WebhookController` → `AnalyzeFacade` → `GrafanaService` / `LokiService` / `AiModelService`

1. Return early if `GrafanaAlertingRequest.isResolved()`
2. Build Loki stream selector from `GrafanaAlert.lokiLabels()` (`job`, `instance`, `namespace`, `pod`, `container`, etc.)
3. Compute time range via `GrafanaAlert.lokiStartNano(bufferMinutes=5)` / `lokiEndNano()`
   - If `endsAt` is `0001-...` (zero-value), use current time
4. Call `GET {loki.url}/loki/api/v1/query_range`
5. Alert context + Loki logs → LLM analysis via `AiModelService.getChatModel()`

### Loki Integration Prerequisite
Prometheus metric labels and Loki stream labels must be identical (`job`, `instance`, etc.). If Promtail/Grafana Alloy is not configured with the same label set, log query results will be empty.

## application.yml Key Settings

```yaml
spring:
  data.redis.port: 6379
  profiles.active: ${SPRING_PROFILES_ACTIVE:local}

loki:
  url: ""   # Loki base URL (e.g. http://loki:3100)
```

## Language

- The default language of this project is **English**.
- All comments, descriptions, and documentation (except test code and `README.md`) must be written in English.

## Validation Rules

- All input validation must be performed **server-side** (Service layer). Do not add client-side validation in JavaScript.

## Code Style

- Controller method parameter definitions must not be line-broken — keep `@Parameter`, `@PathVariable`, `@RequestBody`, etc. on a single line with the parameter. Example:
  ```kotlin
  fun getApp(@Parameter(description = "Application name", required = true) @PathVariable name: String): AppGitResponse
  ```

## Layer Responsibilities

### Controller
- Responsible only for receiving the request body and returning the response DTO.
- Must not contain any business logic — even trivial logic must be delegated to a Service or Facade.
- Return type must always be a DTO. Do not use `ResponseEntity`.

### Service
- Owns a single, well-scoped area of business logic or external API integration. Adhere to the Single Responsibility Principle.
- Implements the detailed business logic for a specific domain (e.g., token management, LLM invocation, record persistence).
- Must not inject other Service beans at the same layer. Cross-service orchestration belongs exclusively in the Facade layer.
- Every new Service and every new public method added to an existing Service must have a corresponding test. All public methods must be covered.

### Facade
- Defines and implements the sequencing and orchestration of business logic across multiple Services.
- Coordinates calls to Services in the correct order to fulfill a use case (e.g., fetch diff → call LLM → save record → push via WebSocket).
- Does not contain detailed business logic itself; delegates to the appropriate Services.

## Data Class Rules

- No more than one data class per Kotlin file. (e.g., `AiConfigRequest` and `AiConfigResponse` must be in separate files)
- DTOs used by Controllers go in the `controller.dto` package; DTOs used by connectors go in the `connector.dto` package. (e.g., `GrafanaAlertingRequest` → `controller.dto`, `LokiQueryResult` → `connector.dto`)

## Test Rules

- Test classes go under `src/test/kotlin`, organized by layer package. (e.g., `com.walter.spring.ai.ops.service.AiModelServiceTest`)
- Test method names follow the `given[Condition]_when[Action]_then[ExpectedResult]` pattern. (e.g., `givenValidLlmConfig_whenGetChatModel_thenReturnsChatModel`)
- Tests must be independent — a failure in one test must not affect others. Use mocking where necessary.
- Use `// given`, `// when`, `// then` comments to clearly separate Arrange, Act, and Assert sections.
- Test method names must be in English; Korean descriptions go in the `@DisplayName` annotation.

## External Service Integration

**Grafana**: Contact Point → Webhook, URL `http://<host>:8080/webhook/grafana`

**GitHub / GitLab**: Webhooks → Payload URL `http://<host>:8080/webhook/git/{app}`, Content-type `application/json`, Events: `push`
