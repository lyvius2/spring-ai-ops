package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.AlertingStatus
import com.walter.spring.ai.ops.connector.dto.LokiQueryInquiry
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.controller.dto.GrafanaAlert
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GrafanaService
import com.walter.spring.ai.ops.service.LokiService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.util.concurrent.Executor

@ExtendWith(MockitoExtension::class)
class AnalyzeFacadeTest {

    @Mock private lateinit var applicationService: ApplicationService
    @Mock private lateinit var grafanaService: GrafanaService
    @Mock private lateinit var lokiService: LokiService
    @Mock private lateinit var aiModelService: AiModelService
    @Mock private lateinit var messagingTemplate: SimpMessagingTemplate

    // CompletableFuture.runAsync()를 동기 실행으로 전환해 비동기 타이밍 문제 방지
    private val syncExecutor: Executor = Executor { it.run() }

    private lateinit var analyzeFacade: AnalyzeFacade

    @BeforeEach
    fun setUp() {
        analyzeFacade = AnalyzeFacade(
            applicationService, grafanaService, lokiService,
            aiModelService, messagingTemplate, syncExecutor
        )
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
        status: String = "firing",
        alerts: List<GrafanaAlert> = listOf(createAlert()),
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
        title = "Test Alert",
        state = status,
        message = "test message",
    )

    /** firing 경로에서 공통으로 필요한 서비스 Mock 설정 */
    private fun stubHappyPath(request: GrafanaAlertingRequest) {
        `when`(grafanaService.convertLogInquiry(request))
            .thenReturn(LokiQueryInquiry(query = "{job=\"test-app\"}", start = "0", end = "0"))
        `when`(lokiService.executeLogQuery(any(LokiQueryInquiry::class.java)))
            .thenReturn(LokiQueryResult())
        `when`(aiModelService.executeAnalyzeFiring(any(String::class.java), any(String::class.java)))
            .thenReturn("Root cause: timeout in PaymentService")
    }

    // ── analyzeFiring ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolved 요청이면 RESOLVED 상태 반환")
    fun analyzeFiring_returnsResolved_whenRequestIsResolved() {
        // given
        val request = createRequest(status = "resolved")

        // when
        val result = analyzeFacade.analyzeFiring(request, "my-app")

        // then
        assertThat(result).isEqualTo(AlertingStatus.RESOLVED)
    }

    @Test
    @DisplayName("resolved 요청이면 어떤 서비스도 호출되지 않음")
    fun analyzeFiring_callsNoServices_whenRequestIsResolved() {
        // given
        val request = createRequest(status = "resolved")

        // when
        analyzeFacade.analyzeFiring(request, "my-app")

        // then
        verifyNoInteractions(applicationService, grafanaService, lokiService, aiModelService, messagingTemplate)
    }

    @Test
    @DisplayName("firing 요청이면 FIRING 상태 반환")
    fun analyzeFiring_returnsFiring_whenRequestIsFiring() {
        // given
        val request = createRequest()
        stubHappyPath(request)

        // when
        val result = analyzeFacade.analyzeFiring(request, "my-app")

        // then
        assertThat(result).isEqualTo(AlertingStatus.FIRING)
    }

    @Test
    @DisplayName("firing 요청이면 애플리케이션을 등록함")
    fun analyzeFiring_registersApplication_whenRequestIsFiring() {
        // given
        val request = createRequest()
        stubHappyPath(request)

        // when
        analyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(applicationService).addApp("my-app")
    }

    @Test
    @DisplayName("firing 요청이면 Loki 로그를 조회함")
    fun analyzeFiring_executesLogQuery_whenRequestIsFiring() {
        // given
        val request = createRequest()
        stubHappyPath(request)

        // when
        analyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(lokiService).executeLogQuery(any(LokiQueryInquiry::class.java))
    }

    @Test
    @DisplayName("firing 요청이면 LLM으로 분석을 실행함")
    fun analyzeFiring_executesAiAnalysis_whenRequestIsFiring() {
        // given
        val request = createRequest()
        stubHappyPath(request)

        // when
        analyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(aiModelService).executeAnalyzeFiring(any(String::class.java), any(String::class.java))
    }

    @Test
    @DisplayName("firing 요청이면 분석 레코드를 Redis에 비동기 저장함")
    fun analyzeFiring_savesAnalysisRecord_whenRequestIsFiring() {
        // given
        val request = createRequest()
        stubHappyPath(request)

        // when
        analyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(grafanaService).saveAnalyzeFiringRecord(any(AnalyzeFiringRecord::class.java))
    }

    @Test
    @DisplayName("firing 요청이면 분석 레코드를 WebSocket으로 비동기 전송함")
    fun analyzeFiring_pushesAnalysisRecordViaWebSocket_whenRequestIsFiring() {
        // given
        val request = createRequest()
        stubHappyPath(request)

        // when
        analyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(messagingTemplate).convertAndSend(eq("/topic/firing"), any(AnalyzeFiringRecord::class.java))
    }

    @Test
    @DisplayName("resolved 요청이면 분석 레코드가 저장되지 않음")
    fun analyzeFiring_doesNotSaveRecord_whenRequestIsResolved() {
        // given
        val request = createRequest(status = "resolved")

        // when
        analyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(grafanaService, never()).saveAnalyzeFiringRecord(any(AnalyzeFiringRecord::class.java))
    }
}

