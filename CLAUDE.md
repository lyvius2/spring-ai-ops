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

A Spring Boot application that receives Grafana Alerting and Git (GitHub/GitLab) webhooks and performs error analysis and code review using LLMs (OpenAI, Anthropic, DeepSeek).

**Tech stack**: Spring Boot 3.4.4 · Kotlin · Java (records) · Spring AI 1.1.0 · Redis · Mustache · OpenFeign · Resilience4j · JGit

## Key Design Decisions

### Spring AI AutoConfiguration Fully Disabled
All Spring AI AutoConfiguration classes are explicitly listed under `spring.autoconfigure.exclude` in `application.yml`. LLM clients are instantiated directly in `AiModelService` without auto-configuration.

### Dynamic ChatModel Creation and Persistence
- User selects LLM provider (openai / anthropic / deepseek) and enters an API key via the UI
- `POST /api/llm/config` → API key encrypted with AES-256-GCM (`CryptoProvider`) and saved to Redis (`llmApis` key as JSON array) → `AiModelService` creates a `ChatModel` and holds it in a `@Volatile` field
- `POST /api/llm/select-provider` → switches the active provider without re-entering the key
- On restart, `@EventListener(ApplicationStartedEvent)` reads `llmApis` and `usageLlm` from Redis to restore `ChatModel` automatically
- Models: OpenAI `gpt-4o-mini`, Anthropic `claude-sonnet-4-6`, DeepSeek `deepseek-v4-pro` (overridable via `application.yml`)

### ChatModel Construction
Because AutoConfiguration is disabled, `ToolCallingManager.builder().build()`, `RetryUtils.DEFAULT_RETRY_TEMPLATE`, and `ObservationRegistry.NOOP` are composed manually.

### Embedded Redis for Local Development
`EmbeddedRedisConfig` starts a Redis server in `@PostConstruct` and shuts it down in `@PreDestroy`. The port follows `spring.data.redis.port` in `application.yml`.

## Package Structure

```
src/main/java/com/walter/spring/ai/ops/
└── record/                         # Java records (immutable Redis-persisted data)
    ├── AnalyzeFiringRecord         # Grafana firing analysis result
    ├── CodeReviewRecord            # Git push code review result
    ├── CodeRiskRecord              # Code risk analysis result
    ├── CodeRiskIssue               # Single code risk issue (file, severity, etc.)
    ├── CommitSummary               # Commit metadata summary
    ├── ChangedFile                 # File changed in a commit
    ├── SourceCodeSuggestion        # LLM-suggested source code change
    └── Administrator               # Admin account record

src/main/kotlin/com/walter/spring/ai/ops/
├── code/                           # Enums and constants
│   ├── AlertingStatus              # FIRING / RESOLVED
│   ├── AlertMessageType            # WebSocket message type
│   ├── ConnectionStatus            # LLM connection status
│   ├── GitRemoteProvider           # GITHUB / GITLAB
│   ├── LlmProvider                 # OPEN_AI / ANTHROPIC / DEEP_SEEK
│   ├── ObservabilityProvider       # LOKI / PROMETHEUS
│   ├── RedisKeyConstants           # All Redis key name constants
│   └── RepositoryCloneStatus       # RUNNING / SUCCESS / FAILED
├── config/                         # Spring configuration
│   ├── annotation/
│   │   ├── AdminOnly               # AOP annotation: admin-only method
│   │   └── Facade                  # Meta-annotation: marks Facade beans
│   ├── aspect/
│   │   └── AdminOnlyAspect         # Enforces @AdminOnly via SecurityContextHolder
│   ├── base/
│   │   ├── DynamicConnectorConfig  # Base class for runtime-URL Feign configs
│   │   └── LenientMapper           # ObjectMapper variant tolerating unknown fields
│   ├── exception/
│   │   ├── UnauthorizedException   # 401 — maps to GlobalExceptionHandler
│   │   └── ForbiddenException      # 403 — maps to GlobalExceptionHandler
│   ├── AuthenticationInterceptor   # Enforces login on PROTECTED_ROUTES
│   ├── CsrfTokenInterceptor        # Validates X-CSRF-Token for /api/code-risk/**
│   ├── CsrfTokenProvider           # Generates per-startup CSRF token
│   ├── EmbeddedRedisConfig         # Embedded Redis lifecycle (local profile)
│   ├── GlobalExceptionHandler      # Maps exceptions → JSON error responses
│   ├── MapperConfig                # Primary ObjectMapper bean
│   ├── PasswordChangeRequiredInterceptor # Blocks /api/** if password change pending
│   ├── RepositoryProperties        # repository.* config binding
│   ├── SecurityConfig              # Spring Security: session-only, no form login
│   ├── SwaggerConfig               # SpringDoc / OpenAPI setup
│   ├── VirtualThreadConfig         # Virtual thread executor + LLM rate-limit Semaphore
│   ├── WebMvcConfig                # Interceptor registry
│   ├── WebSocketConfig             # STOMP WebSocket endpoint
│   └── *ConnectorConfig            # Per-connector Feign configs (Loki, Prometheus, GitHub, GitLab)
├── connector/                      # Feign HTTP clients for external APIs
│   ├── GithubConnector             # GitHub REST API
│   ├── GitlabConnector             # GitLab REST API
│   ├── LokiConnector               # Loki query_range API
│   ├── PrometheusConnector         # Prometheus query_range API
│   ├── *FallbackFactory            # Circuit-breaker fallbacks (return empty/null)
│   └── dto/                        # External API response DTOs
├── controller/                     # REST controllers (thin: receive → delegate → return DTO)
│   └── dto/                        # Request/Response DTOs for controllers
├── event/
│   ├── RateLimitHitEvent           # Published when LLM rate limit is hit
│   └── RateLimitHitEventListener   # Pushes rate-limit notice via WebSocket
├── facade/                         # Orchestration across multiple services
│   ├── ApplicationFacade           # App CRUD + Git repo registration
│   ├── CodeRiskFacade              # Code risk analysis (single-call & map-reduce)
│   ├── DashboardFacade             # Dashboard data aggregation
│   ├── GitRemoteFacade             # GitHub/GitLab config orchestration
│   └── ObservabilityFacade         # Grafana firing analysis flow
├── service/                        # Single-domain business logic
│   ├── dto/                        # Internal service DTOs
│   ├── AdminService                # Admin account management
│   ├── AiModelService              # ChatModel lifecycle + LLM invocations
│   ├── ApplicationService          # Application CRUD (Redis)
│   ├── GitRemoteService            # GitHub/GitLab token & URL management
│   ├── GithubService               # GitHub API wrapper
│   ├── GitlabService               # GitLab API wrapper
│   ├── GrafanaService              # Firing record persistence
│   ├── IncidentSourceContextService # Stack-trace parsing + source snippet extraction
│   ├── LokiService                 # Loki log queries
│   ├── MessageService              # WebSocket push messages
│   ├── PrometheusService           # Prometheus metric queries
│   └── RepositoryService           # JGit clone/sync + code risk record persistence
└── util/                           # Stateless helpers
    ├── extension/                  # Kotlin extension functions
    │   ├── RedisExtensions         # zSetPushWithTtl, zSetRangeAllDesc, getArrayList
    │   ├── PathExtensions          # resolveSourceFile
    │   ├── StringExtensions        # extractSourceSnippet
    │   └── ...
    ├── CodeAnalysisResultHandler   # JSON parse/sanitize for LLM JSON output
    ├── CryptoProvider              # AES-256-GCM encrypt/decrypt
    ├── MetricHandler               # Prometheus result → metric DTO conversion
    ├── RedisLockManager            # Distributed Redis lock (acquire/release/withLock)
    └── StackTraceParser            # Extracts stack frames from raw log text
```

## API Endpoints

Server port: **7079** (management: **7081**). Swagger UI: `/swagger-ui.html`.

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/` | - | Main SPA (Mustache) |
| POST | `/api/auth/login` | - | Log in → creates session |
| POST | `/api/auth/logout` | - | Log out → invalidates session |
| POST | `/api/auth/password` | - | Change own password |
| POST | `/api/auth/admin` | Login + AdminOnly | Create additional admin account |
| GET | `/api/auth/admins` | Login + AdminOnly | List admin accounts |
| DELETE | `/api/auth/admins` | Login + AdminOnly | Remove admin accounts |
| GET | `/api/apps` | - | List registered applications |
| GET | `/api/apps/{name}` | - | Get application Git config |
| POST | `/api/apps` | Login | Register new application |
| PUT | `/api/apps/{name}` | Login | Update application Git config |
| DELETE | `/api/apps/{name}` | Login | Remove application |
| GET | `/api/llm/status` | - | LLM connection status |
| POST | `/api/llm/config` | Login | Save LLM API key for a provider |
| POST | `/api/llm/select-provider` | Login | Switch active LLM provider |
| GET | `/api/loki/status` | - | Loki configuration status |
| POST | `/api/loki/config` | Login | Save Loki URL |
| GET | `/api/prometheus/status` | - | Prometheus configuration status |
| GET | `/api/prometheus/application-metrics` | - | Query Prometheus metrics for an app |
| POST | `/api/prometheus/config` | Login | Save Prometheus URL |
| POST | `/api/github/config` | Login | Save GitHub URL & token |
| GET | `/api/github/config/status` | - | GitHub configuration status |
| POST | `/api/code-risk` | Login + CSRF | Trigger async code risk analysis |
| GET | `/api/code-risk/{app}/list` | - | Get code risk analysis records |
| GET | `/api/firing/{app}/list` | - | Get Grafana firing analysis records |
| GET | `/api/commit/{app}/list` | - | Get code review records |
| POST | `/webhook/grafana[/{app}]` | - | Receive Grafana Alerting webhook |
| POST | `/webhook/git[/{app}]` | - | Receive GitHub/GitLab push webhook |

## Security & Auth Model

Spring Security is used **only** for session management and BCrypt encoding — all URL rules are disabled (`anyRequest().permitAll()`). Auth is enforced by custom interceptors.

| Component | Scope | Behavior |
|---|---|---|
| `AuthenticationInterceptor` | `PROTECTED_ROUTES` list | Throws `UnauthorizedException` (401) if `SecurityContextHolder` has no authenticated session |
| `PasswordChangeRequiredInterceptor` | `/api/**` | Throws `UnauthorizedException` (403 message) if session flag `passwordChangeRequired=true` is set |
| `CsrfTokenInterceptor` | `/api/code-risk/**` | Validates `X-CSRF-Token` header against `CsrfTokenProvider.token` |
| `@AdminOnly` + `AdminOnlyAspect` | Method-level | Throws `ForbiddenException` (403) unless `SecurityContextHolder` username equals `admin` |

**Adding a new protected route**: add a `Route` entry to `AuthenticationInterceptor.PROTECTED_ROUTES` companion object.

**Session flow**: `POST /api/auth/login` → `AdminService.login()` → `SecurityContext` populated → session cookie returned. First login as `admin` forces a password change (session flag).

## Connector Layer

All external HTTP calls go through OpenFeign interfaces in `connector/`. Each connector has:
- A `*Connector` Feign interface with `@FeignClient(url = PLACEHOLDER_URL, configuration = [*ConnectorConfig::class], fallbackFactory = ...)`
- A `*ConnectorConfig` class extending `DynamicConnectorConfig`
- A `*ConnectorFallbackFactory` returning empty/null on circuit open

**`DynamicConnectorConfig`** resolves the target URL **at request time**: checks Redis first (user-configured URL), falls back to `application.yml`. This allows runtime URL changes without restart.

**Circuit breaker**: Resilience4j wraps all Feign calls. TimeLimiter default is 35 s (safety net — Feign read-timeout of 30 s fires first).

**Adding a new connector**: see `SKILLS.md`.

## Redis Storage Patterns

All Redis key names are constants in `RedisKeyConstants`. Never hardcode key strings inline.

| Pattern | Keys | Access method |
|---|---|---|
| Simple string | config URLs, tokens, LLM provider | `opsForValue().get/set` |
| JSON string | `llmApis` (List<LlmConfig>), app git config | `getArrayList()` / `opsForValue()` |
| Sorted set (ZSet) | `firing:*`, `commit:*`, `code:*` | `zSetPushWithTtl()` / `zSetRangeAllDesc()` |
| Distributed lock | `repository:lock:*` | `RedisLockManager.withLock()` |

**ZSet convention**: score = epoch millis. `zSetPushWithTtl` prunes entries older than `retentionHours` on every write. `zSetRangeAllDesc` returns all entries newest-first.

**Credentials**: API keys and tokens are AES-256-GCM encrypted via `CryptoProvider` before storage. `crypto.secret-key` in `application.yml` is required at startup.

## Async & Concurrency

- **Virtual threads**: `VirtualThreadConfig` creates `SimpleAsyncTaskExecutor("Virtual Thread-")` with `setVirtualThreads(true)`. Inject via `@Qualifier("applicationTaskExecutor")`.
- **LLM rate limiter**: `@Qualifier("llmRateLimiter") Semaphore` (capacity = `app.async.virtual.llm-max-concurrency`). `AiModelService` acquires before every LLM call.
- **Map-reduce code analysis**: `CodeRiskFacade` splits large bundles into per-directory chunks, dispatches with `CompletableFuture.supplyAsync(executor)`, and controls parallelism with a run-scoped `Semaphore(mapReduceConcurrency)`.
- **Thread safety**: `AiModelService.chatModel` is `@Volatile` for cross-thread visibility on write.
- **Resilience4j `cancel-running-future: false`**: prevents `Thread.interrupt()` on virtual threads (interrupted virtual threads can cause unexpected behavior).

## Java Records in `record/`

`src/main/java/com/walter/spring/ai/ops/record/` holds **Java records** — not Kotlin data classes. These are the Redis-persisted analysis results. Add new record types here as Java records to keep the pattern consistent.

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
server.port: 7079                    # App port (management: 7081)
spring.data.redis.port: 6379
spring.profiles.active: local        # 'local' starts embedded Redis

crypto.secret-key: <required>        # AES-256-GCM key; startup fails if blank

ai.open-ai.model: gpt-4o-mini
ai.anthropic.model: claude-sonnet-4-6
ai.anthropic.max-tokens: 8192
ai.deepseek.model: deepseek-v4-pro

analysis.result-language: en         # Language for LLM analysis output
analysis.data-retention-hours: 120   # Redis ZSet TTL for analysis records
analysis.maximum-view-count: 5       # Max records returned per app (0 = unlimited)
analysis.code-risk.token-threshold: 27000      # Single-call vs map-reduce threshold
analysis.code-risk.map-reduce-concurrency: 3   # Parallel chunk analyses

repository.stored: false             # true = persist cloned repos to local-path
repository.local-path: ./data/repository

app.async.virtual.executor-concurrency-limit: 200
app.async.virtual.llm-max-concurrency: 20
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
- Must be annotated with `@Facade` (a `@Component` meta-annotation that also serves as a layer marker).

### Connector
- Feign interface + `*ConnectorConfig` (extends `DynamicConnectorConfig`) + `*FallbackFactory`.
- Responsible only for the HTTP call and response mapping. No business logic.
- DTOs used only by connectors go in `connector.dto`; DTOs shared with controllers go in `controller.dto`.

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

**Grafana**: Contact Point → Webhook, URL `http://<host>:7079/webhook/grafana`

**GitHub / GitLab**: Webhooks → Payload URL `http://<host>:7079/webhook/git/{app}`, Content-type `application/json`, Events: `push`
