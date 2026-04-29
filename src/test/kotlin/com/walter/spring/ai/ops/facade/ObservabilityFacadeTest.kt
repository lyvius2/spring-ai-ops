package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.connector.dto.LokiQueryInquiry
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.controller.dto.GrafanaAlert
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.GrafanaService
import com.walter.spring.ai.ops.service.LokiService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.service.PrometheusService
import com.walter.spring.ai.ops.service.RepositoryService
import com.walter.spring.ai.ops.service.dto.AppGitConfig
import org.springframework.core.task.AsyncTaskExecutor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.nio.file.Files

@Suppress("UNCHECKED_CAST")
private fun <T> anyObject(): T = Mockito.any() as T

@ExtendWith(MockitoExtension::class)
class ObservabilityFacadeTest {

    @Mock private lateinit var applicationService: ApplicationService
    @Mock private lateinit var grafanaService: GrafanaService
    @Mock private lateinit var lokiService: LokiService
    @Mock private lateinit var prometheusService: PrometheusService
    @Mock private lateinit var githubService: GithubService
    @Mock private lateinit var gitlabService: GitlabService
    @Mock private lateinit var aiModelService: AiModelService
    @Mock private lateinit var repositoryService: RepositoryService
    @Mock private lateinit var messageService: MessageService
    @Mock private lateinit var taskExecutor: AsyncTaskExecutor

    private lateinit var incidentAnalyzeFacade: ObservabilityFacade

    @BeforeEach
    fun setUp() {
        incidentAnalyzeFacade = ObservabilityFacade(
            applicationService, grafanaService, lokiService, prometheusService,
            githubService, gitlabService, aiModelService, repositoryService, messageService,
            taskExecutor,
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
        // Run tasks synchronously on the calling thread for deterministic test execution
        doAnswer { invocation ->
            (invocation.arguments[0] as Runnable).run()
            null
        }.`when`(taskExecutor).execute(Mockito.any())
        `when`(grafanaService.convertLogInquiry(request))
            .thenReturn(LokiQueryInquiry(query = "{job=\"test-app\"}", start = "0", end = "0"))
        `when`(lokiService.executeLogQuery(anyObject()))
            .thenReturn(LokiQueryResult())
        `when`(prometheusService.isConfigured()).thenReturn(false)
        `when`(aiModelService.executeAnalyzeFiring(anyObject(), anyObject(), anyObject(), anyObject()))
            .thenReturn("Root cause: timeout in PaymentService")
    }

    // ── analyzeFiring ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolved 요청이면 어떤 서비스도 호출되지 않음")
    fun analyzeFiring_callsNoServices_whenRequestIsResolved() {
        // given
        val request = createRequest(status = "resolved")

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verifyNoInteractions(applicationService, grafanaService, lokiService, aiModelService, messageService)
    }

    @Test
    @DisplayName("firing 요청이면 애플리케이션을 등록함")
    fun analyzeFiring_registersApplication_whenRequestIsFiring() {
        // given
        val request = createRequest()
        stubHappyPath(request)

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

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
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(lokiService).executeLogQuery(anyObject())
    }

    @Test
    @DisplayName("Git 설정이 유효하면 checkout 결과를 소스 섹션으로 LLM 분석에 연결함")
    fun analyzeFiring_connectsCheckoutSourcePathToAnalyzePrompt_whenGitConfigIsValid() {
        // given
        val request = createRequest()
        val sourcePath = Files.createTempDirectory("incident-source-context-test")
        stubHappyPath(request)
        `when`(applicationService.getGitConfig("my-app"))
            .thenReturn(AppGitConfig("https://example.com/test.git", "main"))
        `when`(repositoryService.cloneRepository("my-app", "https://example.com/test.git", "main"))
            .thenReturn(sourcePath)
        `when`(aiModelService.executeAnalyzeFiring(anyObject(), anyObject(), anyObject(), sourcePath))
            .thenReturn("Root cause with source context")

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(repositoryService).cloneRepository("my-app", "https://example.com/test.git", "main")
        verify(aiModelService).executeAnalyzeFiring(anyObject(), anyObject(), anyObject(), sourcePath)
    }


    @Test
    @DisplayName("firing 요청이면 분석 레코드를 Redis에 저장함")
    fun analyzeFiring_savesAnalysisRecord_whenRequestIsFiring() {
        // given
        val request = createRequest()
        stubHappyPath(request)

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(grafanaService).saveAnalyzeFiringRecord(anyObject())
    }

    @Test
    @DisplayName("firing 요청이면 분석 레코드를 WebSocket으로 전송함")
    fun analyzeFiring_pushesAnalysisRecordViaWebSocket_whenRequestIsFiring() {
        // given
        val request = createRequest()
        stubHappyPath(request)

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(messageService).pushFiring(anyObject())
    }

    @Test
    @DisplayName("resolved 요청이면 분석 레코드가 저장되지 않음")
    fun analyzeFiring_doesNotSaveRecord_whenRequestIsResolved() {
        // given
        val request = createRequest(status = "resolved")

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(grafanaService, never()).saveAnalyzeFiringRecord(anyObject())
    }
}
