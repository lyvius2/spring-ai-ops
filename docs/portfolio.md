# Spring AI OPS

**GitHub**: https://github.com/lyvius2/spring-ai-ops  
**데모 URL**: https://ai-ops.duckdns.org  
**개발 기간**: 2026년 4월 (약 2주일, 주말 + 평일 저녁)  
**제출 항목**: 항목 1 · 항목 4 · 항목 7

---

## 프로젝트 개요

**Spring AI Ops**는 모니터링과 형상관리 도구체인을 LLM과 연결하는 AI 기반 운영 자동화 도구입니다.  
Git Remote Repository를 통해 전체 소스코드를 수집하여 정적 코드 위험 분석을 수행하고,  
Grafana Alerting을 수신하여 오류 분석, GitHub, GitLab의 webhook을 수신하여 코드 리뷰를 실시간으로 수행하여 결과를 WebSocket 대시보드에 스트리밍합니다.  
Spring AI Ops는 단순한 LLM API 연동을 넘어, 운영 현장에서 실제로 겪는 문제(장애 대응 지연, 코드 리뷰 부담, 대용량 코드 보안 점검)를 AI로 해결하고자 설계한 프로젝트입니다.

| 기능 | 입력 | 출력 |
|------|------|------|
| 정적 코드 위험 분석 | Git 저장소 전체 소스 | 보안 취약점·개선 권고 리포트 |
| 자동 코드 리뷰 | GitHub / GitLab push | 코드 품질·보안 리뷰 리포트 |
| 장애 인텔리전스 | Grafana Alert + Loki 로그 | AI 근본원인 분석 리포트 |

---

## 항목 1 — AI를 활용해 해결한 문제 / 자동화 사례

### 문제 상황

운영 환경에서 장애가 발생했을 때, 그리고 배포 전 코드 품질을 점검할 때 다음과 같은 문제가 있었습니다:

**장애 대응:**
- Grafana 알럿 수신 → Loki에서 로그 수동 검색
- 스택트레이스 해석 및 연관 컴포넌트 수동 추적
- 원인 파악까지 보편적으로 평균 15~30분 이상 소요
- 야간·주말 알럿 발생 시 대응 지연 심화

**코드 품질 점검:**
- 전체 코드베이스 보안 취약점을 사람이 직접 검토하기엔 시간·비용 과다
- 리뷰어마다 기준이 달라 일관성 부족
- 대형 코드베이스는 단순 정적 분석 도구로도 커버리지 한계 존재

### AI 활용 방법

**① 장애 자동 분석**

Grafana Alerting webhook 수신 즉시 아래 파이프라인이 자동 실행됩니다:

```
Grafana Alert 발생
    ↓
POST /webhook/grafana/{application}
    ↓
Loki API 자동 조회 (alert 발생 시각 기준 ±5분)
    ↓
알럿 컨텍스트 + 로그 → LLM(OpenAI / Anthropic) 전달
    ↓
근본 원인 / 영향 범위 / 조치 방법 분석
    ↓
WebSocket /topic/firing → 대시보드 실시간 스트리밍
```

LLM 프롬프트 구성:
- 시스템 역할: `애플리케이션 장애 분석 전문가`로 설정
- 컨텍스트: 알럿 레이블(job, namespace, pod), 심각도, 발생 시각 포함
- 로그: Loki `query_range` API로 수집한 실제 ERROR/WARN 로그 라인 전달
- 출력 형식: 근본 원인 · 영향 범위 · 권고 조치 3단 구조로 요청

**② 정적 코드 위험 분석 (Static Code Risk Analysis)**

대시보드에서 분석 버튼 클릭 한 번으로 전체 코드베이스를 AI가 검토합니다:

```
POST /api/code-risk
    ↓
JGit으로 저장소 클론 (GitHub/GitLab 토큰 자동 적용)
    ↓
소스 파일 수집 → 토큰 수 추정
    ├─ ≤ 27,000 토큰: 단일 LLM 호출
    └─ > 27,000 토큰: Map-Reduce 전략
         청크 분할 → 병렬 분석 (최대 3개 동시, 호출 간 1,000ms 지연)
         → 청크 결과 통합 (최종 LLM 호출)
    ↓
이슈 목록 JSON (파일 · 라인 · 심각도 HIGH/MEDIUM/LOW · 코드 스니펫 · 권고사항)
    ↓
WebSocket /topic/analysis/result → 대시보드 실시간 전달
```

LLM 프롬프트 구성:
- 시스템 역할: `보안 및 코드 품질 전문가`로 설정
- 입력: 파일 경로 + 전체 소스코드 번들 (또는 청크)
- 출력 형식: Markdown 요약 리포트 + 구조화된 JSON 이슈 목록 동시 요청
- 이슈 항목: 파일명, 라인 번호, 심각도, 설명, 문제 코드 스니펫, 수정 권고사항

### 개선 결과

| 구분 | Before          | After                 |
|------|-----------------|-----------------------|
| 장애 원인 파악 시간 | 15~30분 (보편적 기준) | 1~2분 이내 (95% 단축)      |
| 야간 장애 대응 | 담당자 수동 로그 탐색    | 알럿 발생 즉시 AI 분석 결과 수신  |
| 전체 코드 보안 점검 | 수동 코드 리뷰 (수 시간) | 버튼 클릭 → 수분 내 전체 이슈 목록 |
| 분석 일관성 | 리뷰어마다 기준 상이     | LLM 기준으로 표준화          |

---

## 항목 4 — AI를 개발 워크플로에 통합한 사례

### 4-1. GitHub / GitLab 자동 코드 리뷰 파이프라인

`git push` 이벤트 발생 시 LLM이 자동으로 커밋 diff를 리뷰하고 결과를 대시보드에 전달합니다.

```
git push
    ↓  (GitHub Webhook / GitLab Webhook)
POST /webhook/git/{application}
    ├─ X-Gitlab-Event 헤더 감지 → GitLab 파싱
    └─ 그 외 → GitHub 파싱
    ↓
커밋 diff 수집 (GitHub Compare API / GitLab Repository API)
    ↓
변경 파일별 diff → LLM 코드 리뷰
    (코드 품질 · 잠재 버그 · 보안 · 개선 제안)
    ↓
WebSocket /topic/commit → 대시보드 실시간 전달
```

주요 구현 포인트:
- GitHub 초기 push(before SHA = `0000...`) 케이스 별도 처리
- GitLab / GitHub을 단일 엔드포인트(`/webhook/git`)에서 `X-Gitlab-Event` 헤더로 자동 분기
- 코드 리뷰 결과는 Redis에 저장, 애플리케이션별 히스토리 관리

### 4-2. 정적 코드 위험 분석 — Map-Reduce 전략

LLM의 컨텍스트 윈도우 한계를 극복하기 위해 Map-Reduce 전략을 설계·구현했습니다.

**설계 배경:**  
전체 코드베이스를 LLM에 한 번에 전달하면 토큰 초과 오류 발생. 단순히 잘라서 보내면 파일 간 연관성이 손실되어 분석 품질 저하. 이를 해결하기 위해 Map(청크별 분석) → Reduce(결과 통합) 구조를 도입했습니다.

```
소스 파일 수집 → 토큰 수 추정
    ├─ ≤ 27,000 토큰
    │       → 단일 LLM 호출 (전체 번들 전달)
    │
    └─ > 27,000 토큰
            → [Map 단계]
               청크 분할 → 각 청크 병렬 LLM 분석
               (최대 3개 동시, 호출 간 1,000ms 지연)
            → [Reduce 단계]
               청크별 결과 취합 → 최종 통합 LLM 호출
               (중복 이슈 제거, 전체 요약 생성)
```

| 설계 결정 | 이유 |
|-----------|------|
| 토큰 임계값 27,000 | LLM 응답 토큰 여유분 확보를 위한 실험적 결정 |
| 청크 간 1,000ms 지연 | LLM API Rate Limit(429) 회피 |
| 병렬 최대 3개 | Rate Limit과 처리 속도 사이의 균형점 |
| 진행 상황 WebSocket 스트리밍 | 클론·청크 분석·통합 각 단계를 사용자에게 실시간 전달 |
| CSRF 보호 적용 | `/api/code-risk/**` 에 HTML meta 태그 기반 CSRF 토큰 검증 |

---

## 항목 7 — LLM 기반 기능 개발 경험

### 7-1. 기술 스택

| 구분 | 기술 | 비고 |
|------|------|------|
| LLM | Spring AI 1.1.0 | OpenAI / Anthropic 동적 전환 |
| 모델 | gpt-4o-mini / claude-sonnet-4-6 | 런타임 전환, 재시작 불필요 |
| 언어 | Kotlin 2.2 / Java 21 | Virtual Thread 활용 |
| HTTP Client | Spring Cloud OpenFeign | Resilience4j Circuit Breaker 연동 |
| 실시간 | Spring WebSocket (STOMP) | SockJS 폴백 지원 |
| 저장소 | Redis | RDB 미사용, Embedded Redis 지원 |

### 7-2. OpenAI / Anthropic 동적 전환

Spring AI AutoConfiguration을 전면 비활성화하고, `AiModelService`에서 `ChatModel`을 직접 빌드합니다.  
UI에서 제공자를 변경해도 **애플리케이션 재시작 없이** 즉시 새 모델이 활성화됩니다.

```kotlin
// AutoConfiguration 비활성화 → ChatModel 직접 빌드
val model = OpenAiChatModel.builder()
    .openAiApi(OpenAiApi(apiKey))
    .defaultOptions(
        OpenAiChatOptions.builder()
            .model(config.model)
            .build()
    )
    .toolCallingManager(ToolCallingManager.builder().build())
    .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
    .build()
```

### 7-3. Virtual Thread + Semaphore 기반 LLM 동시 호출 제어

Java 21 Virtual Thread로 LLM 호출을 비동기 처리하면서, Semaphore로 동시 호출 수를 제어합니다.

```kotlin
// Virtual Thread: 무제한 executor (IO 블로킹 시 OS carrier thread 반환)
// Semaphore: LLM API 동시 호출 수 상한 (기본 20)
semaphore.acquire()
try {
    return chatModel.call(prompt)
} finally {
    semaphore.release()
}
```

### 7-4. LLM 응답 신뢰성 강화

**JSON 복구 파서 (`CodeAnalysisResultHandler`):**  
정적 코드 위험 분석 시 LLM이 이슈 목록 JSON을 반환할 때 구분자 누락·잘린 JSON 등 malformed 응답이 발생하는 경우가 있었습니다. 단순 파싱 실패로 처리하지 않고 부분 데이터도 최대한 복구하는 fallback 파서를 구현했습니다.

```
정상 JSON 파싱 시도
    ↓ 실패
delimiter 기반 복구 파싱 시도
    ↓ 실패
부분 JSON 추출 후 유효한 이슈만 선택적 반환
```

**Rate Limit 실시간 알림:**  
429 응답 수신 시 `RateLimitHitEvent`를 발행하고 WebSocket으로 사용자에게 즉시 전달합니다.  
Map-Reduce 분석 중 Rate Limit 발생 시에는 그 시점까지 수집된 청크 결과를 부분 저장합니다.

### 7-5. 토큰 제어 및 비용 관리

| 제어 전략 | 구현 방법 |
|-----------|-----------|
| 모델별 max_tokens 설정 | Anthropic: 8,192 / OpenAI: 모델 기본값 |
| 단일 호출 임계값 | 27,000 토큰 초과 시 Map-Reduce 전환으로 호출당 토큰 분산 |
| 동시 호출 제한 | Semaphore(기본 20)로 LLM API 동시 요청 수 상한 설정 |
| 청크 간 지연 | Map-Reduce 맵 단계에서 1,000ms 딜레이로 Rate Limit 회피 |
| 저비용 모델 선택 | 기본 모델 gpt-4o-mini (고성능 대비 저비용) |

### 7-6. AES-256-GCM 민감정보 암호화

Redis에 저장되는 LLM API 키 및 Git 액세스 토큰을 AES-256-GCM으로 암호화합니다.

- 환경 변수 `CRYPTO_SECRET_KEY` 미설정 시 평문 저장 경고 로그 출력
- 키 변경 시 기존 암호화 값 복호화 불가 → UI에서 재입력 흐름 안내
- 운영 환경에서는 반드시 `CRYPTO_SECRET_KEY` 설정 권고

---

## 개발 기여도

| 영역                             | 본인 기여 | AI 보조 |
|--------------------------------|-------|-------|
| 아키텍처 설계                        | 90%   | 10%   |
| Backend (Kotlin + JAVA)        | 65%   | 35%   |
| Frontend (Mustache + CSS + JS) | 5%    | 95%   |

---

## Screen Shots

### LLM API Key Configuration
*LLM 제공자와 API 키를 UI에서 입력합니다. 애플리케이션 재시작 없이 즉시 모델이 활성화됩니다.*

![LLM API Key Configuration](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/AIConfig.png?raw=true)

### Static Code Risk Analysis
*등록된 Git 저장소를 대상으로 AI 기반 전체 코드 정적 분석을 실행합니다. 이슈는 파일 단위로 그룹화되어 심각도(HIGH / MEDIUM / LOW)와 함께 표시되며, 각 항목에는 문제 코드 스니펫과 개선 권고사항이 포함됩니다.*

![Static Code Risk Analysis](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/CodeRisk.png?raw=true)

### AI-Powered Code Review
*GitHub push 이벤트가 수신되면 LLM이 변경 파일별 diff를 리뷰하여 코드 품질, 잠재적 버그, 보안 고려사항, 개선 제안을 구조화된 보고서로 제공합니다.*

![Code Review](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/CodeReview.png?raw=true)

### Grafana Alerting Webhook
*Grafana 알림이 발생하면 webhook 페이로드가 실시간으로 Spring AI Ops에 전달됩니다. 대시보드에서 알림 상태와 레이블을 확인할 수 있습니다.*

![Grafana Alerting Webhook](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/GrafanaAlerting.png?raw=true)

### AI-Powered Error Analysis
*LLM이 Grafana 알림 컨텍스트와 Loki 로그를 함께 분석하여 근본 원인, 영향 범위, 조치 방법을 대시보드에 실시간으로 스트리밍합니다.*

![Firing Analysis](https://github.com/lyvius2/spring-ai-ops/blob/main/docs/FiringAnalyze.png?raw=true)
