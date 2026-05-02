# Spring AI Ops

![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-7F52FF?logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.4-6DB33F?logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.0-6DB33F)
![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2024.0.1-6DB33F)
![Redis](https://img.shields.io/badge/Redis-enabled-DC382D?logo=redis&logoColor=white)
![OpenFeign](https://img.shields.io/badge/OpenFeign-client-2C3E50)
![Prometheus](https://img.shields.io/badge/Prometheus-optional-E6522C?logo=prometheus&logoColor=white)
![Loki](https://img.shields.io/badge/Loki-logs-F46800?logo=grafana&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green)

AI 기반 운영 자동화 도구로, **Grafana Alerting**, **GitHub**, **GitLab** 웹훅을 수신하여 LLM(OpenAI, Anthropic)으로 오류 분석, 코드 리뷰, 정적 코드 위험 분석을 실시간으로 수행합니다. Grafana 알림 분석 시에는 Loki 로그를 조회하고, Prometheus URL이 설정되어 있으면 같은 알림 시간 구간의 Prometheus 메트릭도 함께 수집합니다. 또한 등록된 애플리케이션 Git 저장소를 checkout하고, JVM stack trace와 관련된 소스 코드 snippet만 추출하여 LLM에 전달합니다. 결과는 WebSocket 기반 라이브 대시보드로 전달됩니다.

---

## 목차

- [프로젝트 개요](#프로젝트-개요)
- [주요 기능](#주요-기능)
- [아키텍처](#아키텍처)
- [인터페이스 흐름](#인터페이스-흐름)
  - [정적 코드 위험 분석](#정적-코드-위험-분석)
  - [GitHub → LLM (코드 리뷰)](#github--llm-코드-리뷰)
  - [GitLab → LLM (코드 리뷰)](#gitlab--llm-코드-리뷰)
  - [Grafana → Loki + Prometheus → LLM (장애 분석)](#grafana--loki--prometheus--llm-장애-분석)
- [스크린샷](#스크린샷)
- [기술 스택](#기술-스택)
- [시작하기](#시작하기)
  - [사전 요구사항](#사전-요구사항)
  - [설정](#설정)
  - [민감 정보 암호화](#민감-정보-암호화)
  - [실행](#실행)
  - [Grafana 설정](#grafana-설정)
  - [GitHub Webhook 설정](#github-webhook-설정)
  - [GitLab Webhook 설정](#gitlab-webhook-설정)
- [API 레퍼런스](#api-레퍼런스)
- [API 문서 (Swagger)](#api-문서-swagger)
- [라이선스](#라이선스)

---

## 프로젝트 개요

**Spring AI Ops**는 모니터링 및 형상관리 도구체인을 LLM과 연결하는 AI 기반 운영 자동화 도구입니다. 세 가지 AI 워크플로우를 제공합니다.

1. **정적 코드 위험 분석** — 요청 시 등록된 Git 저장소를 클론하고 전체 소스 트리를 스캔하여 LLM에게 보안·품질 종합 리뷰를 요청합니다. 대용량 코드베이스는 청크로 분할하여 병렬 분석(맵-리듀스)한 뒤 단일 최종 보고서로 통합합니다. 결과에는 마크다운 보고서와 구조화된 JSON 이슈 목록(심각도, 파일, 라인, 권고사항)이 포함됩니다.

2. **자동 코드 리뷰** — GitHub 또는 GitLab push webhook이 수신되면 커밋 diff를 가져와 LLM에게 정확성·보안·성능·코드 품질 관점의 자동 코드 리뷰를 수행합니다.

3. **인시던트 인텔리전스** — Grafana 알림이 발생하면 알림 레이블로 Loki 스트림 셀렉터를 만들고, 같은 레이블과 시간 구간을 사용해 Prometheus 범위 조회도 병렬로 수행할 수 있습니다. 동시에 등록된 소스 저장소를 checkout하고, Loki 로그에서 JVM stack trace를 파싱해 관련 소스 파일과 라인 주변 snippet을 추출합니다. LLM은 알림, 로그, 메트릭, 소스 컨텍스트를 함께 분석하여 근본 원인 보고서와 구조화된 소스 수정 권고안을 반환합니다.

모든 분석 결과는 STOMP WebSocket을 통해 실시간으로 브라우저에 전달됩니다.

관계형 데이터베이스는 사용하지 않으며, Redis를 유일한 저장소로 사용합니다. 로컬 개발 환경에서는 Embedded Redis가 자동으로 기동되므로 별도 설치가 필요 없습니다.

> **데모 사이트**: [https://ai-ops.duckdns.org](https://ai-ops.duckdns.org)

---

## 주요 기능

| 기능 | 설명 |
|---|---|
| **정적 코드 위험 분석** | Git 저장소를 클론하여 AI 기반 전체 코드베이스 리뷰 수행 — 보안 취약점, 코드 품질 이슈, 개선 권고사항. 코드베이스 크기에 따라 단일 호출 또는 맵-리듀스 전략 자동 선택 |
| **자동 코드 리뷰** | GitHub / GitLab 커밋 diff → 코드 품질, 잠재적 버그, 보안 고려사항 |
| **LLM 장애 분석** | Grafana 알림 컨텍스트 + Loki 로그 + Prometheus 메트릭 시계열(선택) + stack trace 관련 소스 snippet → 근본 원인, 영향 범위, 관련 파일, 조치 방법 |
| **소스 수정 권고안** | 장애 분석 결과에 구조화된 소스 수정 권고안(`filePath`, `originalCode`, `suggestionCode`, `description`, `lineNumber`) 포함. AI Analysis 하단에서 파일 경로를 클릭하면 원본 코드와 수정 제안을 좌우 비교 popup으로 확인하고 복사 가능 |
| **Persistent Repository Storage** | `repository.local-path` 하위에 등록 애플리케이션 저장소를 선택적으로 보관하여 반복 checkout 비용을 줄입니다. Redis lock으로 branch switch/reset 작업을 보호하고, 실패 시 기존 임시 clone 방식으로 fallback합니다 |
| **실시간 대시보드** | 분석 완료 시 WebSocket STOMP으로 브라우저에 즉시 전달 |
| **동적 LLM 전환** | 재시작 없이 UI에서 OpenAI / Anthropic 전환 |
| **다중 애플리케이션** | 여러 애플리케이션 등록 가능, 분석 히스토리가 애플리케이션별로 분리 |
| **RDB 미사용** | Redis만 사용, 로컬 개발 시 Embedded Redis 자동 기동 |
| **Virtual Thread** | 웹훅 핸들러는 즉시 응답, 분석은 Java 21 가상 스레드에서 비동기 처리. LLM API 호출은 별도 Semaphore로 동시 호출 수 제한 (기본: 20) |

---

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Browser (SPA)                               │
│  Mustache + STOMP/SockJS  ←─── WebSocket /topic/firing             │
│                           ←─── WebSocket /topic/commit             │
└───────────────┬─────────────────────────────────────────────────────┘
                │ HTTP
┌───────────────▼─────────────────────────────────────────────────────┐
│                  Spring Boot Application                            │
│                                                                     │
│  WebhookController  ──►  AnalyzeFacade                             │
│  (POST /webhook/*)        │                                         │
│                           ├─ ApplicationService  ──► Redis          │
│  AiConfigController       ├─ GrafanaService      ──► Redis          │
│  LokiConfigController     ├─ GithubService       ──► Redis          │
│  ApplicationController    ├─ GitlabService       ──► Redis          │
│  FiringController         ├─ LokiService         ──► Loki API       │
│  CommitController         ├─ PrometheusService   ──► Prometheus API │
│                           ├─ RepositoryService   ──► Git cache      │
│                           ├─ IncidentSourceContextService           │
│                           ├─ GithubConnector     ──► GitHub API     │
│                           ├─ GitlabConnector     ──► GitLab API     │
│                           └─ AiModelService      ──► LLM API       │
│                                    │                                │
│                           SimpMessagingTemplate                     │
│                                    │                                │
└────────────────────────────────────┼────────────────────────────────┘
                                     │ WebSocket push
                              Connected browsers
```

**레이어 규칙**

- **Controller** — HTTP 요청을 수신하고 DTO를 반환합니다. 비즈니스 로직을 포함하지 않습니다.
- **Facade** — 단일 유스케이스를 위해 여러 서비스를 조율합니다. `@Facade`로 표시됩니다.
- **Service** — 단일 책임 비즈니스 로직 및 Redis 영속성을 담당합니다.
- **Connector** — Loki, Prometheus, GitHub, GitLab REST API를 위한 OpenFeign 클라이언트입니다.

---

## 인터페이스 흐름

### 정적 코드 위험 분석

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
        ├─ JGit으로 저장소 준비 (토큰 인증 적용)
        │    repository.stored=true이면 persistent sync, 아니면 임시 clone
        │    요청 branch를 fetch, checkout/switch, origin/{branch}로 hard reset
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

> **Persistent repository storage**: `repository.stored=true`이고 `repository.local-path`가 유효하면 Code Risk 분석은 로컬 persistent repository를 재사용하고 요청된 branch를 분석 전에 동기화합니다. 동기화에 실패하면 임시 clone으로 fallback합니다.

> **Git 인증**: Git Remote Configuration에서 등록한 GitHub 또는 GitLab 액세스 토큰이 비공개 저장소 클론에 자동으로 사용됩니다.

---

### GitHub → LLM (코드 리뷰)

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

---

### GitLab → LLM (코드 리뷰)

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

---

### Grafana → Loki + Prometheus → LLM (장애 분석)

```
Grafana Alert 발생
        │
        ▼
POST /webhook/grafana[/{application}]
        │
        ├─ status == "resolved"? → 처리 스킵 (RESOLVED 반환)
        │
        ├─ 첫 번째 firing alert 선택
        │    (firing이 없으면 payload의 첫 번째 alert 사용)
        │
        ├─ 알림 레이블로 셀렉터 생성
        │    Loki 예시:       {job="my-app", namespace="prod", pod="api-xyz"}
        │    Prometheus 예시: {job="my-app", namespace="prod", pod="api-xyz"}
        │
        ├─ 시간 범위 계산
        │    start = alert.startsAt − 5분 버퍼
        │    end   = alert.endsAt  (zero-value이면 현재 시각)
        │
        ├─ Loki 로그 조회
        │    GET {loki.url}/loki/api/v1/query_range
        │
        ├─ Prometheus 메트릭 병렬 조회  ── 선택사항 (prometheus.url이 설정된 경우)
        │    GET {prometheus.url}/api/v1/query_range
        │    같은 알림 레이블로 메트릭 셀렉터 구성
        │    해당 시간 구간에 매칭되는 시계열 전체 반환
        │
        ├─ 등록된 소스 저장소 병렬 준비  ── 선택사항
        │    deploy branch가 설정된 경우에만 수행
        │    설정된 deploy branch 사용
        │    repository.stored=true이면 persistent sync, 아니면 임시 clone
        │    GitHub/GitLab 토큰이 있으면 인증에 사용
        │
        ├─ Loki 로그에서 JVM stack trace 파싱
        │    애플리케이션 frame을 소스 파일로 resolve
        │    stack trace lineNumber 주변의 focused snippet 추출
        │    전체 저장소를 LLM에 전달하지 않음
        │
        ├─ LLM 분석 요청
        │    알림 컨텍스트
        │    + Loki 로그 라인   (있는 경우)
        │    + Prometheus 메트릭 데이터  (있는 경우)
        │    + 관련 소스 snippet  (있는 경우)
        │    → 근본 원인 / 영향 범위 / 관련 파일
        │      / 구체적 수정 가이드 / 소스 수정 권고안 JSON
        │
        ├─ AnalyzeFiringRecord를 Redis에 저장 (key: firing:{application})
        │    Markdown 분석 결과와 구조화된 sourceCodeSuggestions 포함
        │
        └─ WebSocket /topic/firing 으로 결과 Push
                │
                ▼
           브라우저 대시보드에 실시간 분석 결과 표시
```

> **전제 조건**: Prometheus 메트릭 레이블과 Loki 스트림 레이블은 동일한 식별 레이블(`job`, `instance`, `namespace`, `pod` 등)을 공유하는 것이 좋습니다. 그래야 같은 알림 레이블로 로그와 메트릭을 함께 조회할 수 있습니다. Promtail 또는 Grafana Alloy 설정을 확인하세요.

> **Prometheus는 선택사항**: `prometheus.url`이 설정되지 않으면 Loki 로그만으로 분석을 진행합니다. 오류가 발생하지 않습니다.

> **Loki는 필수입니다**: 현재 Grafana 장애 분석 파이프라인은 항상 Loki 조회를 시도합니다. Grafana 웹훅 분석을 사용하려면 먼저 `loki.url`을 설정해야 합니다.

> **소스 컨텍스트는 제한적으로 전송됩니다**: 장애 소스 분석은 stack trace frame을 기준으로 관련 라인 주변의 작은 snippet만 추출합니다. 전체 저장소를 LLM에 보내지 않습니다.

> **deploy branch가 필요합니다**: 등록된 애플리케이션에 `deployBranch`가 없으면 Grafana 장애 분석은 source checkout, snippet 추출, source code suggestion 요청을 수행하지 않습니다. 저장소 기본 branch로 fallback하지 않습니다.

> **장애 소스 checkout 인증**: 등록된 저장소 URL이 GitHub 또는 GitLab이면 설정된 Git Remote token을 자동으로 사용합니다. token은 선택사항이며, public 저장소는 token 없이 checkout할 수 있습니다.

---

## 스크린샷

### LLM API Key 설정
*UI에서 LLM 제공자와 API 키를 입력합니다. 애플리케이션을 재시작하지 않아도 즉시 모델이 활성화됩니다.*

![LLM API Key Configuration](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/AIConfig.png?raw=true)

---

### 정적 코드 위험 분석
*등록된 Git 저장소를 대상으로 AI 기반 전체 정적 분석을 실행합니다. 이슈는 파일 단위로 그룹화되며 심각도(HIGH / MEDIUM / LOW), 문제 코드 스니펫, 권장 수정 사항이 함께 표시됩니다.*

![Static Code Risk Analysis](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/CodeRisk.png?raw=true)

---

### AI 기반 코드 리뷰
*GitHub push 이벤트가 수신되면 LLM이 변경 파일별 커밋 diff를 검토하고, 코드 품질, 잠재 버그, 보안 고려사항, 개선 제안을 포함한 구조화된 보고서를 제공합니다.*

![Code Review](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/CodeReview.png?raw=true)

---

### Grafana Alerting Webhook
*Grafana 알림이 발생하면 웹훅 페이로드가 실시간으로 Spring AI Ops에 전달됩니다. 대시보드에서 알림 상태와 레이블을 바로 확인할 수 있습니다.*

![Grafana Alerting Webhook](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/GrafanaAlerting.png?raw=true)

---

### AI 기반 장애 분석
*LLM이 Grafana 알림 컨텍스트와 Loki 로그, 선택적으로 Prometheus 메트릭 시계열, 그리고 JVM stack trace에서 resolve된 focused source snippet을 함께 분석합니다. 구조화된 소스 수정 권고안은 AI Analysis 하단에 표시되며, 파일 경로를 클릭하면 원본 코드와 수정 제안을 좌우 비교 popup으로 확인할 수 있습니다.*

![Firing Analysis](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/FiringAnalyze.png?raw=true)

---

## 기술 스택

| 구분 | 기술 |
|---|---|
| 언어 | Kotlin 2.2 / Java 21 |
| 프레임워크 | Spring Boot 3.4.4 |
| AI | Spring AI 1.1.0 — OpenAI (`gpt-4o-mini`), Anthropic (`claude-sonnet-4-6`) |
| 관측성 | Loki (로그 조회), Prometheus (메트릭 조회, 선택) |
| 저장소 | Redis (유일한 데이터 저장소, RDB 미사용) |
| 개발용 Redis | Embedded Redis (자동 기동, 별도 설치 불필요) |
| HTTP 클라이언트 | Spring Cloud OpenFeign + Resilience4j Circuit Breaker |
| 실시간 통신 | Spring WebSocket (STOMP over SockJS) |
| 템플릿 | Mustache |
| API 문서 | springdoc-openapi 2.8.3 (Swagger UI) |
| 비동기 | Java 21 Virtual Thread (`SimpleAsyncTaskExecutor` 동시성 제한: 200) + Semaphore 기반 LLM 호출 수 제한 |
| 빌드 | Gradle Kotlin DSL |

---

## 시작하기

### 사전 요구사항

- JDK 21 이상
- Redis에 저장되는 인증 정보를 암호화하기 위한 비어 있지 않은 `CRYPTO_SECRET_KEY`
- OpenAI, Anthropic API 키 (하나 이상)
- Grafana 장애 분석용 접근 가능한 Loki 서버
- (선택) Prometheus 메트릭 조회용 Prometheus 서버 URL
- (선택) Trace 전송을 받을 OTLP 호환 백엔드 또는 OpenTelemetry Collector
- 코드 리뷰 기능 사용 시 GitHub 또는 GitLab Personal Access Token

### 설정

`src/main/resources/application.yml`을 편집하거나 환경 변수를 설정합니다:

```yaml
ai:
  open-ai:
    model: gpt-4o-mini                   # OpenAI 모델 이름
    api-key: ${AI_OPEN_AI_API_KEY:}      # 환경변수 AI_OPEN_AI_API_KEY로도 설정 가능
  anthropic:
    model: claude-sonnet-4-6             # Anthropic 모델 이름
    api-key: ${AI_ANTHROPIC_API_KEY:}    # 환경변수 AI_ANTHROPIC_API_KEY
    max-tokens: 8192                     # Anthropic 모델 최대 출력 토큰 수 (기본: 8192)

loki:
  url: ${LOKI_URL:}                      # Loki 서버 주소 (예: http://localhost:3100) — 인증 미지원

prometheus:
  url: ${PROMETHEUS_URL:}                # Prometheus 서버 주소 (예: http://localhost:9090) — 선택사항

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
  result-language: ${ANALYSIS_RESULT_LANGUAGE:en}  # LLM 분석 결과 언어 (ko, en, ja 등)
  code-risk:
    token-threshold: 27000        # 단일 호출 분석의 최대 토큰 수; 초과 시 맵-리듀스로 전환 (기본: 27000)
    map-reduce-concurrency: 3     # 맵 단계 병렬 청크 분석 최대 동시 수 (기본: 3)
    map-reduce-delay-ms: 1000     # 맵 단계 청크 호출 후 지연 시간 ms (기본: 1000)

app:
  async:
    virtual:
      executor-concurrency-limit: 200  # Virtual Thread 기반 비동기 작업의 최대 동시 실행 수
      llm-max-concurrency: 20          # 동시 LLM API 호출 허용 수 (Semaphore)

repository:
  stored: false                         # true이면 repository.local-path 하위에 저장소를 보관
  local-path: ${REPOSITORY_LOCAL_PATH:./data/repository}
  lock:
    ttl-ms: 30000                       # repository 변경 작업용 Redis lock TTL
    wait-timeout-ms: 15000              # 다른 작업의 repository lock을 기다리는 최대 시간
    retry-interval-ms: 1000             # repository lock 재시도 간격

management:
  tracing:
    sampling:
      probability: 1.0  # Trace 샘플링 비율; 로컬/개발 환경에서는 전체 수집
  otlp:
    tracing:
      transport: http
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://127.0.0.1:4318/v1/traces}
      export:
        enabled: false  # true로 변경하면 OTLP endpoint로 trace 전송

resilience4j:
  timelimiter:
    configs:
      default:
        timeout-duration: 35s         # 안전망 — Feign read-timeout(30s)이 먼저 동작
        cancel-running-future: false  # Virtual Thread 대상 Thread.interrupt() 방지

feign:
  loki:
    connect-timeout: 5000   # Loki 연결 타임아웃 (ms)
    read-timeout: 30000     # Loki 읽기 타임아웃 (ms)
  prometheus:
    connect-timeout: 5000   # Prometheus 연결 타임아웃 (ms)
    read-timeout: 30000     # Prometheus 읽기 타임아웃 (ms)
  github:
    connect-timeout: 5000   # GitHub API 연결 타임아웃 (ms)
    read-timeout: 30000     # GitHub API 읽기 타임아웃 (ms)
  gitlab:
    connect-timeout: 5000   # GitLab API 연결 타임아웃 (ms)
    read-timeout: 30000     # GitLab API 읽기 타임아웃 (ms)
```

동일한 설정에 대해 property 값과 Redis 값이 모두 있으면 Redis 값이 우선 적용됩니다.

**Persistent repository storage**

`repository.stored=false`이면 기존 동작을 유지합니다. Code Risk 분석이나 Grafana source snippet 추출 시 매번 임시 디렉터리에 clone합니다.

`repository.stored=true`이고 `repository.local-path`가 유효하면 Spring AI Ops는 등록된 애플리케이션 저장소를 `repository.local-path` 하위의 deterministic path에 보관합니다. 애플리케이션 등록/수정 API는 Redis metadata 저장 후 즉시 응답하고, background virtual thread에서 저장소를 준비합니다. Code Risk 분석과 Grafana source snippet 추출은 Redis lock을 획득한 뒤 `fetch -> checkout/switch branch -> reset --hard origin/{branch}` 순서로 working tree를 동기화하고 lock을 해제합니다.

persistent repository 준비에 실패하면 분석은 임시 clone 방식으로 fallback합니다. Background checkout 실패는 `/topic/alert`로 대시보드에 전달됩니다. 앱 저장 시 설정된 deploy branch가 유효하지 않으면 저장소 기본 branch로 storage initialization을 수행하고, Redis에 저장된 deploy branch는 초기화됩니다.

`repository.local-path`는 충분한 디스크 공간과 안전한 권한을 가진 경로로 지정해야 합니다. 앱 이름 변경/삭제 시에는 `repository.local-path` 하위로 안전하게 resolve되는 경로만 삭제합니다.

**Tracing 전송**

이 애플리케이션은 Spring Boot Actuator와 `micrometer-tracing-bridge-otel`을 사용하므로 서버 요청과 계측된 클라이언트 호출을 OpenTelemetry trace로 변환할 수 있습니다. Trace를 외부로 전송하려면 `management.otlp.tracing.export.enabled=true`로 변경하고, `management.otlp.tracing.endpoint`를 OpenTelemetry Collector, Tempo, Jaeger 등 OTLP 호환 수신 endpoint로 지정하세요. 기본 HTTP endpoint는 `http://127.0.0.1:4318/v1/traces`입니다. gRPC를 사용할 경우 `management.otlp.tracing.transport`와 Collector endpoint를 함께 맞춰야 합니다.

### 민감 정보 암호화

Redis에 저장되는 API 키와 액세스 토큰은 **AES-256-GCM** 방식으로 암호화되어 보관됩니다.

`crypto.secret-key`는 필수입니다. 값이 비어 있으면 애플리케이션은 보안 설정 오류를 크게 로그로 남기고 시작 과정에서 종료됩니다.

```bash
# 환경 변수 권장
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
| `crypto.secret-key` 미설정 | 애플리케이션 시작 실패 및 요청 처리 전 종료 |
| 키를 변경한 경우 | 기존 암호화 값 복호화 불가 — UI에서 API 키를 재입력하면 새 키로 재암호화됨 |

### 실행

```bash
# 빌드
./gradlew build

# 실행 (Embedded Redis 자동 기동)
./gradlew bootRun

# 전체 테스트
./gradlew test
```

브라우저에서 `http://localhost:7079`에 접속합니다. yml에 API 키가 설정되어 있으면 자동으로 LLM이 구성됩니다.

### Grafana 설정

1. **Alerting → Contact points → New contact point**
2. 유형: **Webhook**
3. URL: `http://<your-host>:7079/webhook/grafana/{application}`
4. 알림 정책에 연결

> **주의**: 현재 Loki 인증(Basic Auth, Bearer Token 등)은 지원하지 않습니다. 인증 없이 접근 가능한 Loki 엔드포인트만 사용할 수 있습니다.

> 먼저 `loki.url`을 설정하세요. Loki는 Grafana 장애 분석의 기본 데이터 소스이며, Prometheus는 설정된 경우 분석을 보강하는 역할입니다.

> Prometheus 레이블(`job`, `instance`, `namespace`, `pod` 등)과 Loki 스트림 레이블을 맞춰두면 같은 알림 레이블로 메트릭·로그 조회가 자동으로 동작합니다. Promtail 또는 Grafana Alloy 설정을 확인하세요.

### GitHub Webhook 설정

1. **Repository → Settings → Webhooks → Add webhook**
2. Payload URL: `http://<your-host>:7079/webhook/git/{application}`
3. Content type: `application/json`
4. 이벤트: **Just the push event**

GitHub 액세스 토큰(yml 또는 UI에서 설정)은 `repo` read 스코프(클래식 PAT) 또는 `Contents: Read` 권한(세분화된 PAT)이 필요합니다.

> **참고**: GitHub Webhook **Secret**은 현재 지원하지 않습니다. Webhook 설정 시 Secret 필드는 비워두세요.

### GitLab Webhook 설정

1. **Project → Settings → Webhooks → Add new webhook**
2. URL: `http://<your-host>:7079/webhook/git/{application}`
3. **Trigger** 항목에서 **Push events** 선택
4. 저장

GitLab 액세스 토큰(yml 또는 UI에서 설정)은 `read_api` 스코프가 필요합니다. 셀프 호스팅 GitLab 인스턴스를 사용하는 경우 `gitlab.url`을 해당 인스턴스의 API 기본 URL(예: `https://gitlab.example.com/api/v4`)로 설정하세요.

> **참고**: GitLab Webhook **Secret Token**은 현재 지원하지 않습니다. Webhook 설정 시 Secret Token 필드는 비워두세요.

---

## API 레퍼런스

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/` | 대시보드 UI |
| `POST` | `/api/llm/config` | LLM 제공자 + API 키 저장 |
| `POST` | `/api/llm/select-provider` | yml에 두 개의 키가 있을 때 제공자 선택 |
| `GET` | `/api/loki/status` | Loki 설정 상태 조회 |
| `POST` | `/api/loki/config` | Loki 기본 URL 저장 |
| `GET` | `/api/prometheus/status` | Prometheus 설정 상태 조회 |
| `POST` | `/api/prometheus/config` | Prometheus 기본 URL 저장 (선택사항) |
| `GET` | `/api/prometheus/application-metrics` | 등록된 애플리케이션별 Prometheus 메트릭 조회 (메모리, CPU, 레이턴시, HTTP 상태) |
| `POST` | `/api/github/config` | GitHub / GitLab 액세스 토큰 및 기본 URL 저장 |
| `GET` | `/api/github/config/status` | Git 제공자 설정 상태 조회 |
| `GET` | `/api/app/list` | 등록된 애플리케이션 목록 조회 |
| `POST` | `/api/app/add` | 새 애플리케이션 등록 |
| `DELETE` | `/api/app/remove/{application}` | 애플리케이션 삭제 |
| `GET` | `/api/firing/{application}/list` | 애플리케이션의 알림 분석 레코드 조회 |
| `GET` | `/api/commit/{application}/list` | 애플리케이션의 코드 리뷰 레코드 조회 |
| `POST` | `/api/code-risk` | 정적 코드 위험 분석 실행 |
| `GET` | `/api/code-risk/{application}/list` | 정적 분석 레코드 조회 |
| `POST` | `/webhook/grafana[/{application}]` | Grafana Alerting 웹훅 수신 |
| `POST` | `/webhook/git[/{application}]` | GitHub / GitLab push 웹훅 수신 |

**WebSocket 토픽** (STOMP over SockJS, `/ws`)

| Topic | Payload | 트리거 시점 |
|---|---|---|
| `/topic/firing` | `AnalyzeFiringRecord` | LLM 오류 분석 완료 |
| `/topic/commit` | `CodeReviewRecord` | LLM 코드 리뷰 완료 |
| `/topic/analysis/status` | `String` | 정적 분석 진행 상황 업데이트 (클론 / 청크 / 통합) |
| `/topic/analysis/result` | `CodeRiskRecord` | 정적 분석 완료 |
| `/topic/alert` | `AlertMessage` | Background repository checkout 관련 사용자 확인 필요 |

---

## API 문서 (Swagger)

[springdoc-openapi](https://springdoc.org/)를 통해 Swagger UI를 제공합니다. 애플리케이션 실행 후 아래 URL에서 확인할 수 있습니다.

| URL | 설명 |
|---|---|
| `http://localhost:7079/swagger-ui.html` | Swagger UI — 모든 REST 엔드포인트를 브라우저에서 직접 테스트 가능 |
| `http://localhost:7079/v3/api-docs` | OpenAPI 3.0 명세 (JSON) |
| `http://localhost:7079/v3/api-docs.yaml` | OpenAPI 3.0 명세 (YAML) |

webhook 페이로드나 설정 엔드포인트를 별도 도구 없이 Swagger UI에서 바로 테스트할 수 있습니다.

---

## 라이선스

이 프로젝트는 [MIT License](LICENSE) 하에 오픈소스로 공개되어 있습니다.
