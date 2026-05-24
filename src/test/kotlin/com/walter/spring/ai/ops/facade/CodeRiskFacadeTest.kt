package com.walter.spring.ai.ops.facade

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.record.CodeRiskRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.service.RepositoryService
import com.walter.spring.ai.ops.service.dto.CodeChunk
import com.walter.spring.ai.ops.util.CodeAnalysisResultHandler
import com.walter.spring.ai.ops.util.GitRemoteResolver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mock
import org.mockito.Mockito.never
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
    @Mock private lateinit var gitRemoteResolver: GitRemoteResolver
    @Mock private lateinit var messageService: MessageService
    @Mock private lateinit var sourcePath: Path

    private lateinit var facade: CodeRiskFacade

    /** Runs CompletableFuture tasks inline (synchronously) for deterministic tests. */
    private val inlineExecutor = Executor { it.run() }

    private val githubUrl = "https://github.com/org/repo"

    @BeforeEach
    fun setUp() {
        val lenientMapper = ObjectMapper().apply {
            configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
            configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
        }
        val handler = CodeAnalysisResultHandler(lenientMapper)
        facade = CodeRiskFacade(
            repositoryService, aiModelService, applicationService,
            gitRemoteResolver, messageService, handler,
            inlineExecutor,
            tokenThreshold = 27000,
            mapReduceConcurrency = 3,
            mapReduceDelayMs = 0L,
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeRecord(appName: String = "my-app", url: String = githubUrl, success: Boolean = true): CodeRiskRecord =
        CodeRiskRecord(LocalDateTime.now(), appName, url, "main", null, success, "## Summary", emptyList())

    /**
     * Stubs the full single-call happy path.
     * getToken() is stubbed to return null (no token configured).
     * prepareRepository uses nullable(String::class.java) for the token param
     * to match the null that flows from getToken().
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
        `when`(gitRemoteResolver.getToken(gitUrl)).thenReturn(null)
        `when`(repositoryService.prepareRepository(anyString(), anyString(), anyString(), nullable(String::class.java)))
            .thenReturn(sourcePath)
        `when`(repositoryService.collectSourceFiles(sourcePath)).thenReturn(files)
        `when`(repositoryService.buildBundle(sourcePath, files)).thenReturn("bundle")
        `when`(aiModelService.estimateTokenCount("bundle")).thenReturn(tokenCount)
        `when`(aiModelService.executeAnalyzeCodeRisk("bundle")).thenReturn(rawResponse)
        `when`(repositoryService.saveAnalyzedResult(anyString(), anyString(), anyString(), anyString(), anyList(), nullable(String::class.java)))
            .thenReturn(returnRecord)
    }

    // ── analyze — single-call path ─────────────────────────────────────────────

    @Test
    @DisplayName("토큰 수가 임계값 이하이면 단일 호출로 executeAnalyzeCodeRisk 한 번 호출")
    fun givenTokensBelowThreshold_whenAnalyze_thenCallsExecuteAnalyzeCodeRiskOnce() {
        // given
        stubSingleCallHappyPath(tokenCount = 1000)

        // when
        facade.analyze("my-app", "main", null)

        // then
        verify(aiModelService).executeAnalyzeCodeRisk("bundle")
        verify(aiModelService, never()).executeFinalAnalyzeCode(anyList())
    }

    @Test
    @DisplayName("분석 완료 후 분석 레코드를 WebSocket으로 전송")
    fun givenValidResponse_whenAnalyze_thenPushesAnalysisResultViaWebSocket() {
        // given
        val record = makeRecord()
        stubSingleCallHappyPath(returnRecord = record)

        // when
        facade.analyze("my-app", "main", null)

        // then
        verify(messageService).pushAnalysisResult(record)
    }

    @Test
    @DisplayName("prepareRepository에서 예외 발생 시 analyze에서 예외가 전파됨")
    fun givenPrepareRepositoryThrows_whenAnalyze_thenExceptionPropagates() {
        // given — prepareRepository is outside CompletableFuture, so exception propagates directly
        `when`(applicationService.getGitRepoByAppName("my-app")).thenReturn(githubUrl)
        `when`(gitRemoteResolver.getToken(githubUrl)).thenReturn("gh-token")
        `when`(repositoryService.prepareRepository("my-app", githubUrl, "main", "gh-token"))
            .thenThrow(RuntimeException("Repository preparation failed"))

        // when / then
        assertThatThrownBy { facade.analyze("my-app", "main", null) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("Repository preparation failed")
    }

    @Test
    @DisplayName("LLM 호출 실패 시 Redis에 저장하지 않음")
    fun givenLlmThrows_whenAnalyze_thenDoesNotSaveRecord() {
        // given
        stubSingleCallHappyPath(tokenCount = 500)
        `when`(aiModelService.executeAnalyzeCodeRisk("bundle"))
            .thenThrow(RuntimeException("LLM unavailable"))

        // when
        facade.analyze("my-app", "main", null)

        // then
        verify(repositoryService, never()).saveAnalyzedResult(
            anyString(), anyString(), anyString(), anyString(), anyList(), nullable(String::class.java)
        )
    }

    // ── analyze — map-reduce path ──────────────────────────────────────────────

    @Test
    @DisplayName("맵-리듀스 분석 완료 후 최종 레코드를 WebSocket으로 전송")
    fun givenTokensAboveThreshold_whenAnalyze_thenPushesMapReduceResult() {
        // given
        val files = listOf<Path>()
        val chunk = CodeChunk("pkg", "bundle-chunk")
        val record = makeRecord()

        `when`(applicationService.getGitRepoByAppName("my-app")).thenReturn(githubUrl)
        `when`(gitRemoteResolver.getToken(githubUrl)).thenReturn("gh-token")
        `when`(repositoryService.prepareRepository("my-app", githubUrl, "main", "gh-token")).thenReturn(sourcePath)
        `when`(repositoryService.collectSourceFiles(sourcePath)).thenReturn(files)
        `when`(repositoryService.buildBundle(sourcePath, files)).thenReturn("bundle")
        `when`(aiModelService.estimateTokenCount("bundle")).thenReturn(50000)
        `when`(repositoryService.createChunks(sourcePath, files)).thenReturn(listOf(chunk))
        `when`(aiModelService.executeAnalyzeCodeRisk("bundle-chunk")).thenReturn("## Chunk")
        `when`(aiModelService.executeFinalAnalyzeCode(listOf("## Chunk"))).thenReturn("## Final")
        `when`(repositoryService.saveAnalyzedResult(anyString(), anyString(), anyString(), anyString(), anyList(), nullable(String::class.java)))
            .thenReturn(record)

        // when
        facade.analyze("my-app", "main", null)

        // then
        verify(messageService).pushAnalysisResult(record)
    }

    // ── getCodeRiskRecords ─────────────────────────────────────────────────────

    @Test
    @DisplayName("저장된 레코드가 없으면 빈 리스트 반환")
    fun givenNoRecordsInRedis_whenGetRecords_thenReturnsEmptyList() {
        // given
        `when`(repositoryService.getCodeRiskRecords("my-app")).thenReturn(emptyList())

        // when
        val result = facade.getCodeRiskRecords("my-app")

        // then
        assertThat(result).isEmpty()
    }
}
