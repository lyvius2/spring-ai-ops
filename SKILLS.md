# SKILLS.md

Recipes for common development tasks in this project.

---

## Add a new API endpoint (full stack)

**1. Controller** — add a method to an existing `*Controller` or create a new one under `controller/`.
```kotlin
@GetMapping("/{application}/list")
fun listItems(@Parameter(...) @PathVariable application: String): ItemListResponse =
    itemFacade.getItems(application)
```
- Return a DTO, never `ResponseEntity`.
- Keep `@Parameter`, `@PathVariable`, `@RequestBody` on one line with the parameter (no line break).
- Delegate all logic to a Facade or Service.

**2. DTOs** — one class per file, in `controller/dto/` for controller-facing DTOs.

**3. Service** — add the domain logic to an existing Service or create a new one.
- Must not inject other Services; cross-service calls go in a Facade.
- Every new public method needs a test in `src/test/kotlin/…/service/`.

**4. Facade (if orchestration is needed)** — annotate with `@Facade`, inject required Services.

**5. If the route needs login protection** — add a `Route` entry to `AuthenticationInterceptor.PROTECTED_ROUTES`:
```kotlin
Route("GET", "/api/items/", exact = false)
```

**6. If the route needs `@AdminOnly`** — add `@AdminOnly` to the controller method (or Facade method). The AOP aspect checks `SecurityContextHolder` username == `"admin"`.

---

## Add a new LLM provider

1. Add an entry to `LlmProvider` enum with a `key` string (e.g. `"qwen"`).
2. Add `application.yml` settings under `ai.<provider>.*` (model, api-key, base-url if needed).
3. Inject the new `@Value` fields into `AiModelService`.
4. Add a `when` branch in `AiModelService.buildChatModel()`:
   - If the provider is OpenAI-compatible (e.g. DeepSeek, Qwen), use `OpenAiApi.builder().baseUrl(…).apiKey(…)` + `OpenAiChatModel`.
   - If it has a native Spring AI integration, construct the `*ChatModel` manually (AutoConfig is disabled globally).
5. Add a branch in `AiModelService.createLlmDefaultConfig()` to read the yml API key.
6. Add the provider icon SVG to `src/main/resources/static/images/`.

---

## Add a new external connector (Feign)

Use the existing connectors (e.g. `LokiConnector` + `LokiConnectorConfig`) as the template.

**1. ConnectorConfig** — extend `DynamicConnectorConfig`:
```kotlin
class MyConnectorConfig(
    override val redisTemplate: StringRedisTemplate,
    @Value("\${my.url:http://localhost}") override val configuredUrl: String,
    @Value("\${feign.my.connect-timeout:5000}") override val connectTimeout: Long,
    @Value("\${feign.my.read-timeout:30000}") override val readTimeout: Long,
) : DynamicConnectorConfig() {
    companion object { const val PLACEHOLDER_URL = "http://my-placeholder.internal" }
    override val placeholderUrl = PLACEHOLDER_URL
    override val redisUrlKey = RedisKeyConstants.REDIS_KEY_MY_URL   // add to RedisKeyConstants
}
```
Add `feign.my.connect-timeout` / `feign.my.read-timeout` to `application.yml`.

**2. Feign interface**:
```kotlin
@FeignClient(
    name = "myConnector",
    url = MyConnectorConfig.PLACEHOLDER_URL,
    configuration = [MyConnectorConfig::class],
    fallbackFactory = MyConnectorFallbackFactory::class,
)
interface MyConnector {
    @GetMapping("/api/v1/query")
    fun query(@RequestParam params: Map<String, String>): MyQueryResult
}
```

**3. FallbackFactory** — return an empty/null result on circuit open (never throw from a fallback).

**4. Response DTOs** — add to `connector/dto/`, one class per file.

**5. Service** — inject `MyConnector` and wrap calls; add a Redis key constant if the URL is user-configurable.

**6. Register URL in `WebMvcConfig` interceptors** if the config endpoint needs auth.

---

## Add a new analysis record type

Records in `src/main/java/…/record/` are Java records persisted as JSON in Redis ZSets.

1. Create a new Java record in `record/`:
```java
public record MyAnalysisRecord(
    LocalDateTime occupiedAt,
    String application,
    String result,
    LocalDateTime completedAt
) { }
```

2. Add a Redis key constant to `RedisKeyConstants`:
```kotlin
const val REDIS_KEY_MY_ANALYSIS_PREFIX = "myanalysis:"
```

3. Persist using the ZSet helper (score = epoch millis, automatic TTL pruning):
```kotlin
redisTemplate.zSetPushWithTtl("$REDIS_KEY_MY_ANALYSIS_PREFIX$appName",
    objectMapper.writeValueAsString(record), retentionHours)
```

4. Read with:
```kotlin
redisTemplate.zSetRangeAllDesc("$REDIS_KEY_MY_ANALYSIS_PREFIX$appName")
    .mapNotNull { runCatching { objectMapper.readValue(it, MyAnalysisRecord::class.java) }.getOrNull() }
```

---

## Add a new Redis key

1. Add a constant to `RedisKeyConstants.Companion`.
2. Use `opsForValue()` for scalar values, `zSetPushWithTtl` / `zSetRangeAllDesc` for ordered lists, `getArrayList` for JSON-serialized lists.
3. If the value is a credential, encrypt with `CryptoProvider.encrypt()` before storing and decrypt with `CryptoProvider.decrypt()` after reading.