# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# 빌드
./gradlew build

# 실행 (내장 Redis 자동 기동)
./gradlew bootRun

# 테스트 전체
./gradlew test

# 단일 테스트 클래스
./gradlew test --tests "com.walter.spring.ai.ops.SomeTest"

# 컴파일만
./gradlew compileKotlin
```

## 아키텍처 개요

Grafana Alerting / GitHub webhook을 수신해 OpenAI 또는 Anthropic LLM으로 오류 분석·코드 리뷰를 수행하는 Spring Boot 애플리케이션.

**기술 스택**: Spring Boot 3.4.4 · Kotlin · Spring AI 1.1.0 · Redis · Mustache

## 핵심 설계 결정

### Spring AI AutoConfiguration 전체 비활성화
`application.yml`의 `spring.autoconfigure.exclude`에 모든 Spring AI AutoConfiguration 클래스를 명시적으로 나열한다. LLM 클라이언트는 자동 구성 없이 `AiClientService`에서 직접 인스턴스를 생성한다.

### ChatModel 동적 생성 및 영속화
- UI(`GET /`)에서 LLM 종류(openai/anthropic)와 API Key 입력
- `POST /api/llm/config` → Redis에 `llm`, `llmKey` 키로 저장 → `AiClientService`가 `ChatModel` 인스턴스 생성 후 `@Volatile` 필드에 보관
- 앱 재시작 시 `@PostConstruct`에서 Redis 값을 읽어 `ChatModel` 자동 복원
- OpenAI: `gpt-4o-mini` / Anthropic: `claude-3-5-sonnet-20241022` 고정

### ChatModel 생성 방식
AutoConfiguration이 비활성화되어 있으므로 `ToolCallingManager.builder().build()`, `RetryUtils.DEFAULT_RETRY_TEMPLATE`, `ObservationRegistry.NOOP`을 직접 조합한다.

### 개발 환경 내장 Redis
`EmbeddedRedisConfig`가 `@PostConstruct`로 Redis 서버를 기동하고 `@PreDestroy`로 종료한다. 포트는 `application.yml`의 `spring.data.redis.port`를 따른다.

## 패키지 구조

```
com.walter.spring.ai.ops
├── code/
│   ├── ConnectionStatus        # LLM 연결 결과 enum (SUCCESS, READY, FAILURE)
│   └── AlertingStatus          # Grafana webhook 처리 결과 enum (FIRING, RESOLVED)
├── config/
│   └── EmbeddedRedisConfig     # 개발용 내장 Redis 기동/종료
├── controller/
│   ├── IndexController         # GET /  → index.mustache 렌더링
│   ├── AiConfigController      # POST /api/llm/config
│   ├── WebhookController       # POST /webhook/grafana  (POST /webhook/github 예정)
│   └── dto/
│       ├── AiConfigRequest / AiConfigResponse
│       ├── GrafanaAlertingRequest   # Grafana webhook 최상위 페이로드
│       ├── GrafanaAlert             # 개별 알림 객체 (lokiLabels, lokiStartNano, lokiEndNano 포함)
│       └── GrafanaAlertingResponse
└── service/
    ├── AiClientService         # ChatModel 생명주기 관리
    └── FiringAnalysisService   # Grafana firing 분석 진입점 (Loki 조회 + LLM 분석 예정)
```

## API 엔드포인트

| Method | Path | 설명 |
|---|---|---|
| GET | `/` | LLM 설정 UI |
| POST | `/api/llm/config` | LLM 종류·API Key 저장 및 ChatModel 생성 |
| POST | `/webhook/grafana` | Grafana Alerting webhook 수신 |
| POST | `/webhook/github` | GitHub push webhook 수신 (예정) |

## Grafana → Loki → LLM 흐름

`WebhookController` → `FiringAnalysisService` 순으로 처리된다.

1. `GrafanaAlertingRequest.isResolved()` 이면 조기 반환
2. `GrafanaAlert.lokiLabels()` 로 Loki 스트림 셀렉터 구성 (`job`, `instance`, `namespace`, `pod`, `container` 등)
3. `GrafanaAlert.lokiStartNano(bufferMinutes=5)` / `lokiEndNano()` 로 시간 범위 계산
   - `endsAt`이 `0001-...`(zero-value)이면 현재 시각 사용
4. `GET {loki.url}/loki/api/v1/query_range` 호출
5. Alert 컨텍스트 + Loki 로그 → `AiClientService.getChatModel()` 로 LLM 분석

### Loki 연동 전제 조건
Prometheus 메트릭 레이블과 Loki 스트림 레이블이 동일해야 한다(`job`, `instance` 등). Promtail/Grafana Alloy에서 동일 레이블 세트로 설정하지 않으면 로그 조회 결과가 비어 있다.

## application.yml 주요 설정

```yaml
spring:
  data.redis.port: 6379
  profiles.active: ${SPRING_PROFILES_ACTIVE:local}

loki:
  url: ""   # Loki base URL (예: http://loki:3100)
```

## Controller 작성 규칙

- Controller는 RequestBody 수신 및 Response 반환만 담당한다. 비즈니스 로직은 사소한 것이라도 반드시 Service 또는 Facade에서 수행한다.
- Controller의 반환 타입은 항상 DTO로 한다. `ResponseEntity`는 사용하지 않는다.

## 외부 서비스 연동 조건

**Grafana**: Contact Point → Webhook, URL `http://<host>:8080/webhook/grafana`

**GitHub** (예정): Webhooks → Payload URL `http://<host>:8080/webhook/github`, Content-type `application/json`, Events: `push`, Secret으로 HMAC-SHA256 검증
