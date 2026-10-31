# Spring AI Ops에 Slack Code Review 알림 기능 추가하기

> Spring Boot 3 + Kotlin 환경에서 GitHub/GitLab push webhook → LLM 코드 리뷰 완료 시 Slack Incoming Webhook으로 결과를 전송하는 기능을 구현한 과정을 기록합니다.

---

## 배경

Spring AI Ops는 GitHub 또는 GitLab에 코드가 push되면 LLM이 커밋 diff를 분석하여 코드 리뷰 결과를 WebSocket으로 브라우저 대시보드에 실시간 전달하는 시스템입니다.

대시보드가 항상 열려 있는 것이 아니다 보니, 코드 리뷰가 완료되어도 놓치는 경우가 종종 있었습니다. 개발자가 가장 많이 접속해 있는 Slack으로 리뷰 결과를 즉시 받아볼 수 있으면 좋겠다는 생각에서 이 기능을 추가하게 되었습니다.

---

## 목표 흐름

```
git push
    │
    ▼  (GitHub/GitLab Webhook)
LLM 코드 리뷰 수행
    │
    ├─ WebSocket → 브라우저 대시보드
    │
    └─ CodeReviewCompletedEvent 발행
            │
            ▼ (비동기 EventListener)
       애플리케이션에 Slack 알림 활성화 여부 확인
            │
            ├─ Markdown → Slack mrkdwn 변환
            │
            └─ POST https://hooks.slack.com/{path}
```

---

## 설계 결정: OpenFeign vs RestClient

처음에는 기존 커넥터 패턴(OpenFeign + `DynamicConnectorConfig`)을 그대로 따르려 했습니다. 그런데 Slack Incoming Webhook은 앱별로 경로(`/services/T.../B.../...`)가 다르기 때문에, OpenFeign의 `@FeignClient(url = ...)` 단일 베이스 URL 모델과 잘 맞지 않습니다.

| 방식 | 장점 | 단점 |
|---|---|---|
| OpenFeign | 기존 패턴 일관성 | 앱별 동적 path를 처리하기 어려움 |
| **RestClient** | 베이스 URL 고정 + path만 런타임 주입 | 에러 처리를 직접 구현해야 함 |

`https://hooks.slack.com`은 `application.yml`에 고정값으로 두고, path(`/services/...`) 부분만 Redis에 앱별로 저장하는 방식을 선택했습니다. RestClient의 `.uri(channelPath)`에 동적 path를 주입하면 깔끔하게 해결됩니다.

```kotlin
// application.yml
slack:
  url: https://hooks.slack.com

// SlackChannelConnector
@Component
class SlackChannelConnector(
    @Value("\${slack.url}")
    private val slackUrl: String,
) {
    private val client = RestClient.create(slackUrl)

    fun sendMessage(message: SlackMessageRequest, channelPath: String): SlackMessageResponse {
        val body = client.post()
            .uri(channelPath)              // /services/T.../B.../...
            .contentType(MediaType.APPLICATION_JSON)
            .body(message)
            .retrieve()
            .body(String::class.java) ?: throw RuntimeException("Failed to send message to Slack")
        return SlackMessageResponse(body)
    }
}
```

---

## 앱 설정 확장

코드 리뷰 Slack 알림은 **애플리케이션별로 활성화 여부와 채널 path를 각각 지정**할 수 있어야 합니다.

기존 `AppConfig`에 두 필드를 추가했습니다.

```kotlin
data class AppConfig @JsonCreator constructor(
    @JsonProperty("gitUrl")       val gitUrl: String?,
    @JsonProperty("deployBranch") val deployBranch: String?,
    @JsonProperty("isSend")       val isSend: Boolean = false,        // 알림 활성화 여부
    @JsonProperty("slackChannel") val slackChannel: String? = null,   // /services/T.../B.../...
)
```

### `isSend` 직렬화 함정

Kotlin에서 `val isSend: Boolean`을 선언하면 Java 바이트코드에서 getter가 `isSend()`가 됩니다. Jackson은 Java Bean 규칙에 따라 `is` 접두사를 제거하고 JSON 키를 **`"send"`** 로 직렬화합니다.

```json
// Jackson 기본 동작 — 잘못됨
{ "send": true }

// @JsonProperty("isSend") 추가 후
{ "isSend": true }
```

컨트롤러 응답 DTO(`AppGitResponse`)와 요청 DTO(`AppUpdateRequest`)의 `isSend` 필드 모두에 `@JsonProperty("isSend")`를 명시적으로 붙여야 합니다. 어느 한쪽만 놓쳐도 프런트엔드에서 `data.isSend`가 `undefined`로 평가되어 토글이 항상 off 상태로 보였습니다.

---

## 이벤트 기반 비동기 알림

코드 리뷰 완료 후 Slack 전송은 기존 흐름(LLM 호출 → Redis 저장 → WebSocket push)에 영향을 주지 않아야 합니다. Spring의 `ApplicationEventPublisher`를 활용해 이벤트를 발행하고, `@EventListener`가 비동기로 처리하도록 구성했습니다.

```kotlin
// 이벤트 정의
class CodeReviewCompletedEvent(
    val review: CodeReviewRecord,
    val applicationName: String,
) : ApplicationEvent(review)

// ObservabilityFacade — 코드 리뷰 완료 후 이벤트 발행
eventPublisher.publishEvent(CodeReviewCompletedEvent(record, appName))

// 리스너 — Slack 전송 여부를 Redis에서 확인하고 전송
@Component
class CodeReviewCompletedEventListener(
    private val slackChannelService: SlackChannelService,
    private val applicationService: ApplicationService,
) {
    @EventListener
    fun onCodeReviewCompleted(event: CodeReviewCompletedEvent) {
        val config = applicationService.getAppConfig(event.applicationName) ?: return
        if (!config.isSend || config.slackChannel.isNullOrBlank()) return
        slackChannelService.sendCodeReviewResult(event.review, config.slackChannel)
    }
}
```

---

## Markdown → Slack mrkdwn 변환

LLM이 생성하는 코드 리뷰 결과는 Markdown 형식입니다. Slack은 고유한 mrkdwn 문법을 사용하므로 변환이 필요합니다.

| Markdown | Slack mrkdwn |
|---|---|
| `**bold**` | `*bold*` |
| `*italic*` | `_italic_` |
| `` `code` `` | `` `code` `` (보존) |
| ```` ```java\n...\n``` ```` | ```` ```\n...\n``` ```` (언어 힌트 제거) |
| `# Header` | `*Header*` |
| `- item` | `• item` |
| `[label](url)` | `<url\|label>` |
| `~~strike~~` | `~strike~` |

변환 시 주의할 순서가 있습니다. italic(`*text*`)을 bold(`**text**`)보다 **먼저** 처리하면 안 됩니다. bold를 `*text*`로 변환한 뒤 italic 정규식을 돌리면, 방금 만든 bold 결과물을 italic으로 오인하기 때문입니다.

```
올바른 순서: italic → bold → ...
```

코드 블록은 내부 텍스트가 다른 변환 규칙에 영향받지 않도록 먼저 플레이스홀더로 보호한 뒤, 모든 변환이 끝난 후 복원합니다.

### 길이 제한

Slack의 section 블록은 텍스트 최대 3,000자를 허용합니다. 긴 코드 리뷰 결과는 잘라내고 `_[message truncated]_`를 붙입니다.

```kotlin
fun truncate(text: String, maxLength: Int = SLACK_MAX_LENGTH): String {
    if (text.length <= maxLength) return text
    val cutPoint = maxLength - TRUNCATION_SUFFIX.length
    return text.substring(0, cutPoint).trimEnd() + TRUNCATION_SUFFIX
}
```

---

## Block Kit 메시지 구성

단순 텍스트 전송 대신 Slack Block Kit으로 구조화된 메시지를 만들었습니다.

```
┌──────────────────────────────────────────────┐
│  [header]  Code Review: my-app [main]        │
│  [section] Repository / Branch / Commit 요약  │
│  [divider]                                   │
│  [section] LLM 리뷰 결과 (mrkdwn)              │
└──────────────────────────────────────────────┘
```

```kotlin
private fun buildSlackMessage(record: CodeReviewRecord): SlackMessageRequest {
    val headerBlock   = SlackBlock.header("Code Review: ${record.application()} [${record.branch()}]")
    val metaBlock     = SlackBlock.section(buildMeta(record))
    val dividerBlock  = SlackBlock(type = "divider")
    val reviewMrkdwn  = markdownConverter.toSlackMrkdwn(record.reviewResult() ?: "")
    val truncated     = markdownConverter.truncate(reviewMrkdwn)

    val blocks = if (truncated.isBlank()) {
        listOf(headerBlock, metaBlock)
    } else {
        listOf(headerBlock, metaBlock, dividerBlock, SlackBlock.section(truncated))
    }
    return SlackMessageRequest(text = "Code Review completed — ${record.application()}", blocks = blocks)
}
```

---

## 트러블슈팅

구현 과정에서 세 가지 오류를 연속으로 만났습니다.

### 1. `400 Bad Request: "invalid_blocks"`

Slack이 Block Kit 페이로드를 거부했습니다. 원인은 두 가지였습니다.

**원인 1 — null 필드 직렬화**

`SlackTextObject`와 `SlackBlock`의 nullable 필드(`emoji: Boolean?`, `blockId: String?`)는 값이 없을 때 Jackson에 의해 `"emoji": null`, `"block_id": null`로 직렬화됩니다. Slack은 일부 블록 타입에서 이를 거부합니다.

```kotlin
// 수정 전
data class SlackTextObject(val type: String, val text: String, val emoji: Boolean? = null)

// 수정 후 — null 필드는 JSON 출력에서 제외
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SlackTextObject(val type: String, val text: String, val emoji: Boolean? = null)
```

**원인 2 — header 블록 150자 제한**

Slack의 `header` 블록은 plain_text 최대 150자를 허용합니다. 팩토리 메서드에서 초과하면 자동으로 자릅니다.

```kotlin
fun header(text: String): SlackBlock {
    val safeText = if (text.length > 150) text.take(150) else text
    return SlackBlock(type = "header", text = SlackTextObject(type = "plain_text", text = safeText))
}
```

**원인 3 — 빈 텍스트 section 블록**

`reviewResult()`가 null이거나 변환 후 빈 문자열이 되면 empty section 블록이 생깁니다. Slack은 text가 없는 section 블록도 거부하므로, 빈 경우 해당 블록을 통째로 제외합니다.

### 2. `UnknownContentTypeException: no suitable HttpMessageConverter for text/html`

메시지는 Slack에 정상적으로 전달되었으나, 응답 역직렬화에서 실패했습니다.

Slack Incoming Webhook의 성공 응답은 다음과 같습니다.

```
HTTP/1.1 200 OK
Content-Type: text/html

ok
```

JSON이 아니라 `text/html`로 내려오는 `"ok"` 문자열입니다. 처음에 `body<SlackMessageResponse>()`로 읽으려 했는데, Jackson은 `text/html`을 JSON으로 처리하려 하지 않아 예외가 발생했습니다.

```kotlin
// 수정 전 — JSON 역직렬화 시도
.body<SlackMessageResponse>()

// 수정 후 — String으로 직접 읽고 래핑
val body = .body(String::class.java)
return SlackMessageResponse(body)
```

`SlackMessageResponse.isSuccess()`는 `body == "ok"` 비교이므로 별도 수정 없이 올바르게 동작합니다.

---

## 최종 클래스 구성

| 파일 | 역할 |
|---|---|
| `connector/SlackChannelConnector.kt` | RestClient 기반 Slack Incoming Webhook POST |
| `connector/dto/SlackMessageRequest.kt` | Webhook 요청 페이로드 (text + blocks) |
| `connector/dto/SlackMessageResponse.kt` | 응답 래퍼 (`isSuccess()` 제공) |
| `connector/dto/SlackBlock.kt` | Block Kit 블록 (`header` / `section` / `divider`) |
| `connector/dto/SlackTextObject.kt` | Block Kit 텍스트 컴포지션 오브젝트 |
| `service/SlackChannelService.kt` | 메시지 빌드 + 전송 오케스트레이션 |
| `util/MarkdownConverter.kt` | Markdown → Slack mrkdwn 변환 + 길이 제한 |
| `event/CodeReviewCompletedEvent.kt` | 코드 리뷰 완료 도메인 이벤트 |
| `event/CodeReviewCompletedEventListener.kt` | 이벤트 수신 → Slack 전송 |
| `service/dto/AppConfig.kt` | `isSend` / `slackChannel` 필드 추가 |
| `controller/dto/AppGitResponse.kt` | `isSend` / `slackChannel` 응답 필드 추가 |
| `controller/dto/AppUpdateRequest.kt` | `isSend` / `slackChannel` 요청 필드 추가 |

---

## 마치며

이번 구현에서 얻은 작은 교훈들을 정리하면 다음과 같습니다.

1. **Kotlin Boolean 프로퍼티 이름에 `is` 접두사를 쓸 때는 `@JsonProperty`를 꼭 명시하자.** Java Bean 규칙이 가져오는 직렬화 불일치는 런타임에서 조용히 틀리게 동작하여 디버그가 어렵습니다.

2. **외부 API 응답의 Content-Type을 먼저 확인하자.** Slack Incoming Webhook처럼 성공 응답이 JSON이 아닌 경우가 드물지 않습니다.

3. **Slack Block Kit을 쓸 때는 null 필드를 직렬화 대상에서 제외하자.** `@JsonInclude(NON_NULL)` 한 줄로 예방할 수 있습니다.

4. **Per-resource 동적 URL을 다루는 HTTP 클라이언트에는 `RestClient`가 OpenFeign보다 적합하다.** OpenFeign은 단일 베이스 URL + 고정 path에 최적화되어 있습니다.

