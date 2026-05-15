package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.connector.dto.LokiQueryInquiry
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.controller.dto.GrafanaAlert
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import com.walter.spring.ai.ops.record.SourceCodeSuggestion
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.GrafanaService
import com.walter.spring.ai.ops.service.IncidentSourceContextService
import com.walter.spring.ai.ops.service.LokiService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.service.PrometheusService
import com.walter.spring.ai.ops.service.RepositoryService
import com.walter.spring.ai.ops.service.dto.AppConfig
import com.walter.spring.ai.ops.service.dto.IncidentSourceContext
import com.walter.spring.ai.ops.service.dto.SourceSnippet
import com.walter.spring.ai.ops.util.CodeAnalysisResultHandler
import org.assertj.core.api.Assertions.assertThat
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
    @Mock private lateinit var incidentSourceContextService: IncidentSourceContextService
    @Mock private lateinit var messageService: MessageService
    @Mock private lateinit var codeAnalysisResultHandler: CodeAnalysisResultHandler
    @Mock private lateinit var taskExecutor: AsyncTaskExecutor

    private lateinit var incidentAnalyzeFacade: ObservabilityFacade

    @BeforeEach
    fun setUp() {
        incidentAnalyzeFacade = ObservabilityFacade(
            applicationService, grafanaService, lokiService, prometheusService,
            githubService, gitlabService, aiModelService, repositoryService, incidentSourceContextService, messageService,
            codeAnalysisResultHandler,
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
        `when`(incidentSourceContextService.createContext(anyObject(), anyObject()))
            .thenReturn(IncidentSourceContext(emptyList(), emptyList(), emptyList()))
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
        verify(applicationService).addApp(AppUpdateRequest("my-app"))
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
    @DisplayName("Git 설정이 유효하면 checkout 결과로 source context를 생성하고 LLM 분석에 연결함")
    fun givenValidGitConfig_whenAnalyzeFiring_thenConnectsSourceContextSectionToAnalyzePrompt() {
        // given
        val request = createRequest()
        val sourcePath = Files.createTempDirectory("incident-source-context-test")
        val sourceContext = IncidentSourceContext(
            frames = emptyList(),
            snippets = listOf(
                SourceSnippet(
                    filePath = "src/main/kotlin/com/example/FooService.kt",
                    startLine = 1,
                    endLine = 3,
                    focusLine = 2,
                    content = ">>    2 | error(\"failed\")",
                )
            ),
            unresolvedFrames = emptyList(),
        )
        stubHappyPath(request)
        `when`(applicationService.getAppConfig("my-app"))
            .thenReturn(AppConfig("https://example.com/test.git", "main"))
        `when`(repositoryService.prepareRepository("my-app", "https://example.com/test.git", "main", null))
            .thenReturn(sourcePath)
        `when`(incidentSourceContextService.createContext(anyObject(), Mockito.eq(sourcePath)))
            .thenReturn(sourceContext)
        `when`(aiModelService.executeAnalyzeFiring(anyObject(), anyObject(), anyObject(), Mockito.contains("Related source snippets")))
            .thenReturn("Root cause with source context")

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(repositoryService).prepareRepository("my-app", "https://example.com/test.git", "main", null)
        verify(incidentSourceContextService).createContext(anyObject(), Mockito.eq(sourcePath))
        verify(aiModelService).executeAnalyzeFiring(anyObject(), anyObject(), anyObject(), Mockito.contains("Related source snippets"))
    }

    @Test
    @DisplayName("GitHub 저장소이면 GitHub token을 checkout에 전달함")
    fun givenGithubGitConfig_whenAnalyzeFiring_thenPassesGithubTokenToCheckout() {
        // given
        val request = createRequest()
        val sourcePath = Files.createTempDirectory("incident-source-context-github-test")
        stubHappyPath(request)
        `when`(applicationService.getAppConfig("my-app"))
            .thenReturn(AppConfig("https://github.com/owner/repo.git", "main"))
        `when`(githubService.getToken()).thenReturn("github-token")
        `when`(repositoryService.prepareRepository("my-app", "https://github.com/owner/repo.git", "main", "github-token"))
            .thenReturn(sourcePath)

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(githubService).getToken()
        verify(repositoryService).prepareRepository("my-app", "https://github.com/owner/repo.git", "main", "github-token")
    }

    @Test
    @DisplayName("GitLab 저장소이면 GitLab token을 checkout에 전달함")
    fun givenGitlabGitConfig_whenAnalyzeFiring_thenPassesGitlabTokenToCheckout() {
        // given
        val request = createRequest()
        val sourcePath = Files.createTempDirectory("incident-source-context-gitlab-test")
        stubHappyPath(request)
        `when`(applicationService.getAppConfig("my-app"))
            .thenReturn(AppConfig("https://gitlab.com/owner/repo.git", "main"))
        `when`(gitlabService.getToken()).thenReturn("gitlab-token")
        `when`(repositoryService.prepareRepository("my-app", "https://gitlab.com/owner/repo.git", "main", "gitlab-token"))
            .thenReturn(sourcePath)

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(gitlabService).getToken()
        verify(repositoryService).prepareRepository("my-app", "https://gitlab.com/owner/repo.git", "main", "gitlab-token")
    }

    @Test
    @DisplayName("deployBranch가 없으면 source checkout 없이 LLM 분석을 수행함")
    fun givenMissingDeployBranch_whenAnalyzeFiring_thenSkipsSourceCheckout() {
        // given
        val request = createRequest()
        stubHappyPath(request)
        `when`(applicationService.getAppConfig("my-app"))
            .thenReturn(AppConfig("https://example.com/test.git", null))

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(repositoryService, never()).prepareRepository(anyObject(), anyObject(), anyObject(), anyObject())
        verify(incidentSourceContextService).createContext(anyObject(), Mockito.isNull())
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
    @DisplayName("LLM 응답에 source code suggestion JSON이 있으면 분석 레코드에 매핑함")
    fun givenAnalyzeResponseWithSuggestionJson_whenAnalyzeFiring_thenMapsSuggestionsToRecord() {
        // given
        val request = createRequest()
        val rawResponse = """
            ## Root cause
            NPE in FooService.
            ---SOURCE_CODE_SUGGESTIONS_JSON_START---
            [{"filePath":"src/main/kotlin/FooService.kt","originalCode":"return foo.name","suggestionCode":"return foo?.name ?: \"\"","description":"Avoid null dereference","lineNumber":42}]
            ---SOURCE_CODE_SUGGESTIONS_JSON_END---
        """.trimIndent()
        val sanitizedJson = """[{"filePath":"src/main/kotlin/FooService.kt","originalCode":"return foo.name","suggestionCode":"return foo?.name ?: \"\"","description":"Avoid null dereference","lineNumber":42}]"""
        stubHappyPath(request)
        `when`(aiModelService.executeAnalyzeFiring(anyObject(), anyObject(), anyObject(), anyObject()))
            .thenReturn(rawResponse)
        `when`(codeAnalysisResultHandler.sanitizeControlChars(sanitizedJson))
            .thenReturn(sanitizedJson)
        `when`(codeAnalysisResultHandler.parseJsonArray(sanitizedJson, SourceCodeSuggestion::class.java))
            .thenReturn(
                listOf(
                    SourceCodeSuggestion(
                        "src/main/kotlin/FooService.kt",
                        "return foo.name",
                        "return foo?.name ?: \"\"",
                        "Avoid null dereference",
                        42,
                    )
                )
            )
        var savedRecord: AnalyzeFiringRecord? = null
        doAnswer { invocation ->
            savedRecord = invocation.arguments[0] as AnalyzeFiringRecord
            null
        }.`when`(grafanaService).saveAnalyzeFiringRecord(anyObject())

        // when
        incidentAnalyzeFacade.analyzeFiring(request, "my-app")

        // then
        verify(grafanaService).saveAnalyzeFiringRecord(anyObject())
        val record = requireNotNull(savedRecord)
        assertThat(record.analyzeResults()).isEqualTo("## Root cause\nNPE in FooService.")
        assertThat(record.sourceCodeSuggestions()).hasSize(1)
        assertThat(record.sourceCodeSuggestions()[0].filePath()).isEqualTo("src/main/kotlin/FooService.kt")
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
