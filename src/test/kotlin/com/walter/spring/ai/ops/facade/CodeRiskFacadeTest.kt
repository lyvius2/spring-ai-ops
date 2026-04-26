package com.walter.spring.ai.ops.facade

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.record.CodeRiskIssue
import com.walter.spring.ai.ops.record.CodeRiskRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.service.RepositoryService
import com.walter.spring.ai.ops.sonar.service.RiskCorrelationService
import com.walter.spring.ai.ops.sonar.service.SonarAnalysisService
import com.walter.spring.ai.ops.sonar.service.SonarService
import com.walter.spring.ai.ops.service.dto.CodeChunk
import com.walter.spring.ai.ops.sonar.service.dto.SonarIssue
import com.walter.spring.ai.ops.sonar.service.dto.SonarScanResult
import com.walter.spring.ai.ops.util.CodeAnalysisResultHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.Executor

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodeRiskFacadeTest {

    @Mock private lateinit var repositoryService: RepositoryService
    @Mock private lateinit var aiModelService: AiModelService
    @Mock private lateinit var applicationService: ApplicationService
    @Mock private lateinit var githubService: GithubService
    @Mock private lateinit var gitlabService: GitlabService
    @Mock private lateinit var messageService: MessageService
    @Mock private lateinit var sonarService: SonarService
    @Mock private lateinit var sonarAnalysisService: SonarAnalysisService
    @Mock private lateinit var riskCorrelationService: RiskCorrelationService
    @Mock private lateinit var sourcePath: Path

    private lateinit var facade: CodeRiskFacade

    /** Runs CompletableFuture tasks inline (synchronously) for deterministic tests. */
    private val inlineExecutor = Executor { it.run() }

    private val githubUrl = "https://github.com/org/repo"
    private val gitlabUrl = "https://gitlab.com/org/repo"

    @BeforeEach
    fun setUp() {
        val lenientMapper = ObjectMapper().apply {
            configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
            configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
        }
        val handler = CodeAnalysisResultHandler(lenientMapper)
        facade = CodeRiskFacade(
            repositoryService, aiModelService, applicationService,
            githubService, gitlabService, messageService, handler,
            sonarService, sonarAnalysisService, riskCorrelationService,
            inlineExecutor,
            tokenThreshold = 27000,
            mapReduceConcurrency = 3,
            mapReduceDelayMs = 0L,
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeRecord(appName: String = "my-app", url: String = githubUrl, success: Boolean = true): CodeRiskRecord =
        CodeRiskRecord(LocalDateTime.now(), appName, url, "main", success, "## Summary", emptyList())

    /**
     * Stubs the full single-call happy path.
     * sonarService returns a failed scan so the Sonar branch returns emptyList() without further setup.
     * riskCorrelationService passes the LLM issue list through unchanged.
     */
    private fun stubSingleCallHappyPath(
        appName: String = "my-app",
        gitUrl: String = githubUrl,
        rawResponse: String = "## Summary",
        tokenCount: Int = 1000,
        returnRecord: CodeRiskRecord = makeRecord(appName, gitUrl),
    ) {
        val files = listOf<Path>()
        `when`(applicationService.getGitRepoByAppName(appName)).thenReturn(gitUrl)
        `when`(githubService.getToken()).thenReturn("gh-token")
        `when`(gitlabService.getToken()).thenReturn("gl-token")
        `when`(repositoryService.cloneRepository(appName, gitUrl, "main", "gh-token")).thenReturn(sourcePath)
        `when`(repositoryService.cloneRepository(appName, gitUrl, "main", "gl-token")).thenReturn(sourcePath)
        `when`(repositoryService.collectSourceFiles(sourcePath)).thenReturn(files)
        `when`(repositoryService.buildBundle(sourcePath, files)).thenReturn("bundle")
        `when`(aiModelService.estimateTokenCount("bundle")).thenReturn(tokenCount)
        `when`(aiModelService.executeAnalyzeCodeRisk("bundle")).thenReturn(rawResponse)
        // Sonar scan fails gracefully → emptyList()
        `when`(sonarService.analyze(sourcePath)).thenReturn(SonarScanResult("my-project", success = false))
        // Correlation pass-through: return first arg (llmIssues) unchanged
        `when`(riskCorrelationService.correlate(anyList() ?: emptyList(), anyList() ?: emptyList()))
            .thenAnswer { it.getArgument(0) }
        // Use anyString()/anyList() — both @NotNull in Mockito, safe with Kotlin non-null params
        `when`(repositoryService.saveAnalyzedResult(anyString(), anyString(), anyString(), anyString(), anyList()))
            .thenReturn(returnRecord)
    }

    // ── analyze — single-call path ─────────────────────────────────────────────

    @Test
    @DisplayName("토큰 수가 임계값 이하이면 단일 호출로 executeAnalyzeCodeRisk 한 번 호출")
    fun givenTokensBelowThreshold_whenAnalyze_thenCallsExecuteAnalyzeCodeRiskOnce() {
        // given
        stubSingleCallHappyPath(tokenCount = 1000)

        // when
        facade.analyze("my-app", "main")

        // then
        verify(aiModelService).executeAnalyzeCodeRisk("bundle")
        verify(aiModelService, Mockito.never()).executeFinalAnalyzeCode(anyList())
    }

    @Test
    @DisplayName("분석 완료 후 분석 레코드를 WebSocket으로 전송")
    fun givenValidResponse_whenAnalyze_thenPushesAnalysisResultViaWebSocket() {
        // given
        val record = makeRecord()
        stubSingleCallHappyPath(returnRecord = record)

        // when
        facade.analyze("my-app", "main")

        // then
        verify(messageService).pushAnalysisResult(record)
    }

    @Test
    @DisplayName("GitHub URL이면 githubService.getToken()으로 액세스 토큰 조회")
    fun givenGithubUrl_whenAnalyze_thenResolvesGithubToken() {
        // given
        stubSingleCallHappyPath(gitUrl = githubUrl)

        // when
        facade.analyze("my-app", "main")

        // then
        verify(githubService).getToken()
        verify(gitlabService, Mockito.never()).getToken()
    }

    @Test
    @DisplayName("GitLab URL이면 gitlabService.getToken()으로 액세스 토큰 조회")
    fun givenGitlabUrl_whenAnalyze_thenResolvesGitlabToken() {
        // given
        stubSingleCallHappyPath(gitUrl = gitlabUrl, returnRecord = makeRecord("my-app", gitlabUrl))

        // when
        facade.analyze("my-app", "main")

        // then
        verify(gitlabService).getToken()
        verify(githubService, Mockito.never()).getToken()
    }

    @Test
    @DisplayName("cloneRepository에서 예외 발생 시 analyze에서 예외가 전파됨")
    fun givenCloneRepositoryThrows_whenAnalyze_thenExceptionPropagates() {
        // given — cloneRepository is outside CompletableFuture, so exception propagates directly
        `when`(applicationService.getGitRepoByAppName("my-app")).thenReturn(githubUrl)
        `when`(githubService.getToken()).thenReturn("gh-token")
        `when`(repositoryService.cloneRepository("my-app", githubUrl, "main", "gh-token"))
            .thenThrow(RuntimeException("Clone failed"))

        // when / then
        assertThatThrownBy { facade.analyze("my-app", "main") }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("Clone failed")
    }

    @Test
    @DisplayName("LLM 호출 실패 시 Redis에 저장하지 않음")
    fun givenLlmThrows_whenAnalyze_thenDoesNotSaveRecord() {
        // given
        stubSingleCallHappyPath(tokenCount = 500)
        `when`(aiModelService.executeAnalyzeCodeRisk("bundle"))
            .thenThrow(RuntimeException("LLM unavailable"))

        // when
        facade.analyze("my-app", "main")

        // then
        verify(repositoryService, Mockito.never()).saveAnalyzedResult(
            anyString(), anyString(), anyString(), anyString(), anyList()
        )
    }

    @Test
    @DisplayName("SonarQube 분석 성공 시 riskCorrelationService.correlate가 호출된다")
    fun givenSonarScanSucceeds_whenAnalyze_thenCorrelationIsInvoked() {
        // given
        stubSingleCallHappyPath()
        val sonarIssues = listOf(SonarIssue("kotlin:S101", "MAJOR", "Foo.kt", 10, "Rename"))
        `when`(sonarService.analyze(sourcePath)).thenReturn(SonarScanResult("my-app", success = true))
        `when`(sonarAnalysisService.extractIssues("my-app")).thenReturn(sonarIssues)

        // when
        facade.analyze("my-app", "main")

        // then
        verify(riskCorrelationService).correlate(anyList() ?: emptyList(), anyList() ?: emptyList())
    }

    @Test
    @DisplayName("SonarQube가 예외를 던지면 LLM 결과만으로 저장이 완료된다")
    fun givenSonarServiceThrows_whenAnalyze_thenSavesWithLlmResultsOnly() {
        // given
        stubSingleCallHappyPath()
        `when`(sonarService.analyze(sourcePath)).thenThrow(RuntimeException("sonar-scanner not found"))

        // when
        facade.analyze("my-app", "main")

        // then — save still happens (Sonar failure is non-fatal)
        verify(repositoryService).saveAnalyzedResult(anyString(), anyString(), anyString(), anyString(), anyList())
    }

    // ── analyze — map-reduce path ──────────────────────────────────────────────

    @Test
    @DisplayName("맵-리듀스 분석 완료 후 최종 레코드를 WebSocket으로 전송")
    fun givenTokensAboveThreshold_whenAnalyze_thenPushesMapReduceResult() {
        // given
        val files = listOf<Path>()
        val chunk = CodeChunk("pkg", "bundle-chunk")

        `when`(applicationService.getGitRepoByAppName("my-app")).thenReturn(githubUrl)
        `when`(githubService.getToken()).thenReturn("gh-token")
        `when`(repositoryService.cloneRepository("my-app", githubUrl, "main", "gh-token")).thenReturn(sourcePath)
        `when`(repositoryService.collectSourceFiles(sourcePath)).thenReturn(files)
        `when`(repositoryService.buildBundle(sourcePath, files)).thenReturn("bundle")
        `when`(aiModelService.estimateTokenCount("bundle")).thenReturn(50000)
        `when`(repositoryService.createChunks(sourcePath, files)).thenReturn(listOf(chunk))
        `when`(aiModelService.executeAnalyzeCodeRisk("bundle-chunk")).thenReturn("## Chunk")
        `when`(aiModelService.executeFinalAnalyzeCode(listOf("## Chunk"))).thenReturn("## Final")
        `when`(sonarService.analyze(sourcePath)).thenReturn(SonarScanResult("my-app", success = false))
        `when`(riskCorrelationService.correlate(anyList() ?: emptyList(), anyList() ?: emptyList()))
            .thenAnswer { it.getArgument(0) }
        val record = makeRecord()
        `when`(repositoryService.saveAnalyzedResult(anyString(), anyString(), anyString(), anyString(), anyList()))
            .thenReturn(record)

        // when
        facade.analyze("my-app", "main")

        // then
        verify(messageService).pushAnalysisResult(record)
    }

    // ── runSonarAnalysis ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("스캔 성공 시 extractIssues 결과를 반환한다")
    fun givenSuccessfulScan_whenRunSonarAnalysis_thenReturnsExtractedIssues() {
        // given
        val sonarIssues = listOf(SonarIssue("kotlin:S101", "MAJOR", "Foo.kt", 10, "Rename"))
        `when`(sonarService.analyze(sourcePath)).thenReturn(SonarScanResult("my-project", success = true))
        `when`(sonarAnalysisService.extractIssues("my-project")).thenReturn(sonarIssues)

        val path = repositoryService.cloneRepository("spring-ai-ops", "https://github.com/lyvius2/spring-ai-ops.git", "main")

        // when
        val result = facade.runSonarAnalysis(sourcePath)

        // then
        assertThat(result).isEqualTo(sonarIssues)
    }

    @Test
    @DisplayName("저장된 레코드가 없으면 빈 리스트 반환")
    fun givenNoRecordsInRedis_whenGetRecords_thenReturnsEmptyList() {
        // given
        `when`(repositoryService.getCodeRiskRecords("my-app")).thenReturn(emptyList())

        // when
        val result = facade.getRecords("my-app")

        // then
        assertThat(result).isEmpty()
    }
}