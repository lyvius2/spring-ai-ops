package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.controller.dto.GrafanaAlert
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class GrafanaServiceTest {

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var objectMapper: ObjectMapper

    @Mock
    private lateinit var listOperations: ListOperations<String, String>

    private lateinit var grafanaService: GrafanaService

    @BeforeEach
    fun setUp() {
        grafanaService = GrafanaService(redisTemplate, objectMapper, retentionHours = 120L, maximumViewCount = 5L)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun createAlert(
        status: String = "firing",
        labels: Map<String, String> = mapOf("job" to "test-app"),
        startsAt: String = "2026-04-12T10:00:00Z",
        endsAt: String = "2026-04-12T10:05:00Z",
    ) = GrafanaAlert(
        status = status,
        labels = labels,
        annotations = emptyMap(),
        startsAt = startsAt,
        endsAt = endsAt,
        generatorURL = "",
        fingerprint = "abc123",
        silenceURL = null,
        dashboardURL = null,
        panelURL = null,
        imageURL = null,
        values = null,
        valueString = null,
    )

    private fun createRequest(
        alerts: List<GrafanaAlert>,
        status: String = "firing",
    ) = GrafanaAlertingRequest(
        receiver = "test-receiver",
        status = status,
        orgId = 1L,
        alerts = alerts,
        groupLabels = emptyMap(),
        commonLabels = emptyMap(),
        commonAnnotations = emptyMap(),
        externalURL = "",
        version = "1",
        groupKey = "",
        truncatedAlerts = 0,
        title = "",
        state = "",
        message = "",
    )

    private fun createRecord(application: String = "test-app") = AnalyzeFiringRecord(
        LocalDateTime.of(2026, 4, 12, 10, 0, 0),
        application,
        createRequest(alerts = listOf(createAlert())),
        LokiQueryResult(),
        "analysis result",
        LocalDateTime.of(2026, 4, 12, 10, 1, 0),
    )

    // ── convertLogInquiry ─────────────────────────────────────────────────────

    @Test
    @DisplayName("firing 상태 alert가 있으면 해당 alert 기준으로 LokiQueryInquiry 반환")
    fun convertLogInquiry_returnsInquiry_whenFiringAlertExists() {
        // given
        val startsAt = "2026-04-12T10:00:00Z"
        val endsAt = "2026-04-12T10:05:00Z"
        val alert = createAlert(status = "firing", labels = mapOf("job" to "test-app"), startsAt = startsAt, endsAt = endsAt)
        val request = createRequest(alerts = listOf(alert))
        val expectedStart = Instant.parse(startsAt).minusSeconds(5 * 60).toEpochMilli().times(1_000_000L).toString()
        val expectedEnd = Instant.parse(endsAt).toEpochMilli().times(1_000_000L).toString()

        // when
        val result = grafanaService.convertLogInquiry(request)

        // then
        assertThat(result.query).isEqualTo("""{job="test-app"}""")
        assertThat(result.start).isEqualTo(expectedStart)
        assertThat(result.end).isEqualTo(expectedEnd)
    }

    @Test
    @DisplayName("firing alert가 없으면 첫 번째 alert 기준으로 LokiQueryInquiry 반환")
    fun convertLogInquiry_usesFirstAlert_whenNoFiringAlert() {
        // given
        val alert = createAlert(status = "resolved", labels = mapOf("job" to "fallback-app"))
        val request = createRequest(alerts = listOf(alert), status = "resolved")

        // when
        val result = grafanaService.convertLogInquiry(request)

        // then
        assertThat(result.query).isEqualTo("""{job="fallback-app"}""")
    }

    @Test
    @DisplayName("firing alert와 resolved alert가 함께 있으면 firing alert 우선 사용")
    fun convertLogInquiry_prefersFiringAlert_whenMixedAlerts() {
        // given
        val resolvedAlert = createAlert(status = "resolved", labels = mapOf("job" to "resolved-app"))
        val firingAlert = createAlert(status = "firing", labels = mapOf("job" to "firing-app"))
        val request = createRequest(alerts = listOf(resolvedAlert, firingAlert))

        // when
        val result = grafanaService.convertLogInquiry(request)

        // then
        assertThat(result.query).contains("firing-app")
        assertThat(result.query).doesNotContain("resolved-app")
    }

    @Test
    @DisplayName("alerts 목록이 비어있으면 IllegalArgumentException 발생")
    fun convertLogInquiry_throwsIllegalArgumentException_whenAlertsIsEmpty() {
        // given
        val request = createRequest(alerts = emptyList())

        // when & then
        assertThatThrownBy { grafanaService.convertLogInquiry(request) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("No alerts found")
    }

    @Test
    @DisplayName("Loki 호환 레이블이 없으면 IllegalArgumentException 발생")
    fun convertLogInquiry_throwsIllegalArgumentException_whenNoLokiCompatibleLabels() {
        // given
        val alert = createAlert(labels = mapOf("severity" to "critical", "region" to "ap-northeast-2"))
        val request = createRequest(alerts = listOf(alert))

        // when & then
        assertThatThrownBy { grafanaService.convertLogInquiry(request) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("No Loki-compatible labels found")
    }

    @Test
    @DisplayName("여러 Loki 레이블이 있으면 stream selector가 올바른 형식으로 생성됨")
    fun convertLogInquiry_buildsCorrectStreamSelector_whenMultipleLokiLabels() {
        // given
        val labels = mapOf("job" to "my-app", "instance" to "localhost:8080")
        val alert = createAlert(labels = labels)
        val request = createRequest(alerts = listOf(alert))

        // when
        val result = grafanaService.convertLogInquiry(request)

        // then
        assertThat(result.query).startsWith("{")
        assertThat(result.query).endsWith("}")
        assertThat(result.query).contains("""job="my-app"""")
        assertThat(result.query).contains("""instance="localhost:8080"""")
    }

    @Test
    @DisplayName("레이블 값에 큰따옴표가 포함된 경우 이스케이프 처리된 stream selector 생성")
    fun convertLogInquiry_escapesQuotesInLabelValues_whenSpecialCharactersPresent() {
        // given
        val alert = createAlert(labels = mapOf("job" to """test"app"""))
        val request = createRequest(alerts = listOf(alert))

        // when
        val result = grafanaService.convertLogInquiry(request)

        // then
        assertThat(result.query).contains("""job="test\"app"""")
    }

    @Test
    @DisplayName("레이블 값에 백슬래시가 포함된 경우 이스케이프 처리된 stream selector 생성")
    fun convertLogInquiry_escapesBackslashInLabelValues_whenSpecialCharactersPresent() {
        // given
        val alert = createAlert(labels = mapOf("job" to """test\app"""))
        val request = createRequest(alerts = listOf(alert))

        // when
        val result = grafanaService.convertLogInquiry(request)

        // then
        assertThat(result.query).contains("""job="test\\app"""")
    }

    @Test
    @DisplayName("endsAt이 zero-value인 경우 end가 현재 시각 기준 nano 값으로 설정됨")
    fun convertLogInquiry_usesCurrentTimeAsEnd_whenEndsAtIsZeroValue() {
        // given
        val beforeCall = Instant.now().toEpochMilli().times(1_000_000L)
        val alert = createAlert(endsAt = "0001-01-01T00:00:00Z")
        val request = createRequest(alerts = listOf(alert))

        // when
        val result = grafanaService.convertLogInquiry(request)

        // then
        val afterCall = Instant.now().toEpochMilli().times(1_000_000L)
        assertThat(result.end.toLong()).isBetween(beforeCall, afterCall)
    }

    // ── getAnalyzeFiringRecords ───────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 레코드가 있으면 역직렬화된 목록 반환")
    fun getAnalyzeFiringRecords_returnsList_whenRedisHasRecords() {
        // given
        val json = """{"application":"my-app"}"""
        val record = createRecord("my-app")
        `when`(redisTemplate.opsForList()).thenReturn(listOperations)
        `when`(listOperations.range("firing:my-app", 0, 4)).thenReturn(listOf(json))
        `when`(objectMapper.readValue(json, AnalyzeFiringRecord::class.java)).thenReturn(record)

        // when
        val result = grafanaService.getAnalyzeFiringRecords("my-app")

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].application).isEqualTo("my-app")
    }

    @Test
    @DisplayName("Redis가 null을 반환하면 빈 목록 반환")
    fun getAnalyzeFiringRecords_returnsEmptyList_whenRedisReturnsNull() {
        // given
        `when`(redisTemplate.opsForList()).thenReturn(listOperations)
        `when`(listOperations.range("firing:my-app", 0, 4)).thenReturn(null)

        // when
        val result = grafanaService.getAnalyzeFiringRecords("my-app")

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("역직렬화에 실패한 항목은 결과에서 제외됨")
    fun getAnalyzeFiringRecords_filtersOutInvalidItems_whenDeserializationFails() {
        // given
        val validJson = """{"valid":true}"""
        val invalidJson = """{"invalid":true}"""
        val record = createRecord()
        `when`(redisTemplate.opsForList()).thenReturn(listOperations)
        `when`(listOperations.range("firing:my-app", 0, 4)).thenReturn(listOf(validJson, invalidJson))
        `when`(objectMapper.readValue(validJson, AnalyzeFiringRecord::class.java)).thenReturn(record)
        `when`(objectMapper.readValue(invalidJson, AnalyzeFiringRecord::class.java)).thenThrow(RuntimeException("Parse error"))

        // when
        val result = grafanaService.getAnalyzeFiringRecords("my-app")

        // then
        assertThat(result).hasSize(1)
    }

    @Test
    @DisplayName("'firing:{application}' key로 maximumViewCount 범위만큼 Redis 조회")
    fun getAnalyzeFiringRecords_queriesRedisWithCorrectKeyAndRange() {
        // given
        `when`(redisTemplate.opsForList()).thenReturn(listOperations)
        `when`(listOperations.range("firing:payment-service", 0, 4)).thenReturn(emptyList())

        // when
        grafanaService.getAnalyzeFiringRecords("payment-service")

        // then
        verify(listOperations).range("firing:payment-service", 0, 4)
    }
}
