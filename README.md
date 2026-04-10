/# Spring AI Ops — Project Guide

## 프로젝트 개요

Grafana Alerting webhook과 GitHub push webhook을 수신하여, OpenAI 또는 Anthropic LLM으로 오류를 분석하고 코드 리뷰를 수행하는 AI 기반 운영 자동화 도구.

---

## 기술 스택

| 항목 | 내용 |
|---|---|
| Framework | Spring Boot 3.4.4 + Kotlin |
| Spring AI | 1.1.0 (OpenAI, Anthropic) |
| 데이터 저장소 | Redis (설정 영속화), Embedded Redis (개발 환경) |
| UI | Mustache 템플릿 (Spring Eureka 스타일) |
| LLM | OpenAI (gpt-4o-mini) 또는 Anthropic (claude-3-5-sonnet-20241022) |

---

## 핵심 설계 결정

- **Spring AI autoconfigure 전체 비활성화**: `application.yml`의 `spring.autoconfigure.exclude`에 모든 Spring AI AutoConfiguration 클래스 명시
- **ChatModel 동적 생성**: UI에서 LLM 종류와 API Key 입력 → Redis 저장 → `AiClientService`에서 `ChatModel` 인스턴스 생성 및 보관
- **앱 재시작 시 복원**: `@PostConstruct`에서 Redis의 `llm` / `llmKey` 키를 읽어 `ChatModel` 자동 재생성
- **Redis 키 규칙**: `llm` → `"openai"` or `"anthropic"`, `llmKey` → API Key 문자열

---

## 구현 목록

### 완료

- [x] LLM 설정 UI (`/`) — OpenAI / Anthropic 선택 + API Key 입력
- [x] LLM 설정 API (`POST /api/llm/config`) — Redis 저장 + ChatModel 생성
- [x] Embedded Redis 자동 기동 (`EmbeddedRedisConfig`)

### 진행 중

- [ ] Grafana Alerting webhook 수신 (`POST /webhook/grafana`)
- [ ] Loki API 연동 — 알림 레이블로 오류 로그 조회
- [ ] Grafana 알림 + Loki 로그 → LLM 분석 → 결과 반환

### 예정

- [ ] GitHub push webhook 수신 (`POST /webhook/github`)
- [ ] 변경된 소스코드 diff 추출
- [ ] diff → LLM 코드 리뷰 → 결과 반환
- [ ] WebSocket을 통한 분석 결과 실시간 스트리밍

---

## Grafana Alert 분석 흐름

```
Grafana Alert 발생
    │
    ▼
POST /webhook/grafana
    │  GrafanaAlertingRequest 역직렬화
    │
    ├─ alerts[].status == "resolved" → 복구 이벤트, 처리 스킵 또는 별도 처리
    │
    ├─ alerts[].labels 추출
    │     └─ job, instance, namespace, pod 등
    │
    ├─ 시간 범위 계산
    │     └─ start = startsAt - 5분 버퍼
    │        end   = endsAt (0001-01-01이면 현재 시각)
    │
    ├─ Loki API 호출
    │     GET /loki/api/v1/query_range
    │     query = {job="...", instance="..."} |= "ERROR"
    │     start / end / limit / direction
    │
    └─ LLM 분석 요청
          컨텍스트: Alert labels + annotations + values + Loki 로그
          → ChatModel.call(prompt)
          → 분석 결과 반환
```

---

## GitHub 코드 리뷰 흐름 (예정)

```
GitHub push 이벤트
    │
    ▼
POST /webhook/github
    │  X-Hub-Signature-256 검증 (HMAC-SHA256)
    │
    ├─ push 이벤트의 commits[].added / modified 파일 목록 추출
    ├─ GitHub API로 각 파일의 diff 조회
    └─ LLM 코드 리뷰 요청
          → 리뷰 결과 반환
```

---

## Loki 연동 전제 조건

Grafana Alert의 `labels`로 Loki 로그를 조회하려면 **Prometheus 메트릭 레이블과 Loki 스트림 레이블이 동일**해야 한다.

| Prometheus 레이블 | Loki 스트림 레이블 | 비고 |
|---|---|---|
| `job` | `job` | 필수 |
| `instance` | `instance` | 필수 |
| `namespace` | `namespace` | K8s 환경 |
| `pod` | `pod` | K8s 환경 |
| `container` | `container` | K8s 환경 |

Promtail 또는 Grafana Alloy 설정에서 Prometheus와 동일한 레이블 세트를 지정해야 한다.

---

## 외부 서비스 설정 요구사항

### Grafana
- Contact Point 유형: **Webhook**
- URL: `http://<host>:8080/webhook/grafana`
- HTTP Method: POST

### Loki
- `application.yml`에 Loki base URL 설정 필요 (예정)
- 인증이 필요한 경우 Basic Auth 또는 Bearer Token 설정 필요

### GitHub (예정)
- Repository → Settings → Webhooks
- Payload URL: `http://<host>:8080/webhook/github`
- Content type: `application/json`
- Secret: HMAC 검증용 시크릿 키 (`application.yml`에 설정)
- Events: `push`

---

## 패키지 구조

```
com.walter.spring.ai.ops
├── SpringAiOpsApplication.kt
├── code/
│   └── ConnectionStatus.kt          # LLM 연결 상태 enum
├── config/
│   └── EmbeddedRedisConfig.kt       # 개발용 내장 Redis 기동
├── controller/
│   ├── IndexController.kt           # GET /  (UI 페이지)
│   ├── AiConfigController.kt        # POST /api/llm/config
│   ├── WebhookController.kt         # POST /webhook/grafana, /webhook/github
│   └── dto/
│       ├── AiConfigRequest.kt
│       ├── AiConfigResponse.kt
│       └── GrafanaAlerting.kt       # Grafana webhook payload DTO
└── service/
    └── AiClientService.kt           # ChatModel 생명주기 관리
```
