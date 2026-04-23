package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.record.CodeRiskIssue
import com.walter.spring.ai.ops.record.CodeRiskRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.PmdService
import com.walter.spring.ai.ops.service.dto.CodeChunk
import com.walter.spring.ai.ops.service.RepositoryService
import com.walter.spring.ai.ops.util.CodeAnalysisResultHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import kotlin.io.path.extension

@Facade
class CodeRiskFacade(
    private val repositoryService: RepositoryService,
    private val aiModelService: AiModelService,
    private val pmdService: PmdService,
    private val applicationService: ApplicationService,
    private val githubService: GithubService,
    private val gitlabService: GitlabService,
    private val messageService: MessageService,
    private val codeAnalysisResultHandler: CodeAnalysisResultHandler,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
    @Value("\${analysis.code-risk.token-threshold:27000}") private val tokenThreshold: Int,
    @Value("\${analysis.code-risk.map-reduce-concurrency:3}") private val mapReduceConcurrency: Int,
    @Value("\${analysis.code-risk.map-reduce-delay-ms:1000}") private val mapReduceDelayMs: Long,
) {
    private val log = LoggerFactory.getLogger(CodeRiskFacade::class.java)

    companion object {
        private const val ISSUES_START = "---ISSUES_JSON_START---"
        private const val ISSUES_END   = "---ISSUES_JSON_END---"
    }

    fun analyze(appName: String, branch: String) {
        val gitRepoUrl = applicationService.getGitRepoByAppName(appName)
        val accessToken = resolveAccessToken(gitRepoUrl)
        val sourcePath = repositoryService.cloneRepository(appName, gitRepoUrl, branch, accessToken)
        val files = repositoryService.collectSourceFiles(sourcePath)
        val bundle = repositoryService.buildBundle(sourcePath, files)
        val tokenCount = aiModelService.estimateTokenCount(bundle)
        log.info("Code risk analysis started — app: {}, estimated tokens: {}", appName, tokenCount)

        val analyzeByAi = CompletableFuture.runAsync( {
            val (markdown, issues) = executeAnalyze(tokenCount, bundle, files, sourcePath)
            messageService.pushAnalysisStatus("Analysis complete. Saving results...")
            val record = repositoryService.saveAnalyzedResult(appName, gitRepoUrl, branch, markdown, issues)
            messageService.pushAnalysisResult(record)
        }, executor).exceptionally { ex ->
            log.error("Code risk analysis failed for app: {}, error: {}", appName, ex.message)
            messageService.pushAnalysisResult(CodeRiskRecord.failure(appName, gitRepoUrl, branch, ex.message))
            null
        }

        if (isJvm(files)) {
            val analyzeByPmd = CompletableFuture.runAsync( {
                pmdService.executeAnalyze(sourcePath).let { markdown ->
                    messageService.pushAnalysisStatus("PMD analysis complete. Saving results...")
                }
            }, executor)
            // TODO : handle PMD result saving and potential errors, possibly in parallel with AI analysis. Compares two results to produce the intersection result.
        }

    }

    private fun isJvm(files: List<Path>): Boolean {
        val languages = files.map { it.extension.lowercase() }.toSet()
        return (("kt" in languages || "kts" in languages) || "java" in languages)
    }

    private fun executeAnalyze(tokenCount: Int, bundle: String, files: List<Path>, sourcePath: Path): Pair<String, List<CodeRiskIssue>> = if (tokenCount <= tokenThreshold) {
        log.info("Strategy: single-call analysis")
        messageService.pushAnalysisStatus("Running single-call analysis (est. $tokenCount tokens)...")
        val raw = aiModelService.executeAnalyzeCodeRisk(bundle)
        parseResponse(raw)
    } else {
        log.info("Strategy: map-reduce analysis")
        messageService.pushAnalysisStatus("Large codebase detected — using map-reduce strategy (${files.size} files)...")
        val chunks = repositoryService.createChunks(sourcePath, files)
        val rawResults = executeMapPhase(chunks)
        val parsedChunks = rawResults.map { parseResponse(it) }
        val markdownParts = parsedChunks.map { it.first }
        val allIssues = parsedChunks.flatMap { it.second }
        messageService.pushAnalysisStatus("Consolidating results from ${chunks.size} chunks...")
        val finalMarkdown = aiModelService.executeFinalAnalyzeCode(markdownParts)
        Pair(finalMarkdown, allIssues)
    }

    fun getRecords(appName: String) = repositoryService.getCodeRiskRecords(appName)

    private fun resolveAccessToken(gitUrl: String): String? {
        val lower = gitUrl.lowercase()
        return when {
            lower.contains("github") -> githubService.getToken()
            lower.contains("gitlab") -> gitlabService.getToken()
            else -> null
        }
    }

    private fun parseResponse(raw: String): Pair<String, List<CodeRiskIssue>> {
        val startIdx = raw.indexOf(ISSUES_START)
        if (startIdx == -1) {
            return Pair(raw.trim(), emptyList())
        }

        val markdown = raw.substring(0, startIdx).trim()
        val afterStart = raw.substring(startIdx + ISSUES_START.length)
        val endIdx = afterStart.indexOf(ISSUES_END)
        val jsonText = (if (endIdx == -1) afterStart else afterStart.substring(0, endIdx)).trim()
        val sanitized = codeAnalysisResultHandler.sanitizeControlChars(jsonText)

        val issues = runCatching {
            codeAnalysisResultHandler.parseJsonArray(sanitized, CodeRiskIssue::class.java)
        }.getOrElse {
            log.warn("Failed to parse issues JSON — attempting recovery: {}", it.message)
            val recovered = codeAnalysisResultHandler.recoverIssuesFromJson(sanitized, CodeRiskIssue::class.java)
            if (recovered.isEmpty()) {
                messageService.pushAnalysisStatus("⚠ Some issue data could not be parsed — continuing with partial results.")
            }
            recovered
        }
        return Pair(markdown, issues)
    }

    private fun executeMapPhase(chunks: List<CodeChunk>): List<String> {
        val semaphore = Semaphore(mapReduceConcurrency)
        val futures = chunks.mapIndexed { idx, chunk ->
            CompletableFuture.supplyAsync({
                semaphore.acquire()
                try {
                    log.info("Analyzing chunk: {}", chunk.label)
                    messageService.pushAnalysisStatus("Analyzing chunk ${idx + 1}/${chunks.size}: ${chunk.label}")
                    val result = aiModelService.executeAnalyzeCodeRisk(chunk.bundle)
                    Thread.sleep(mapReduceDelayMs)
                    result
                } finally {
                    semaphore.release()
                }
            }, executor)
        }
        return futures.map { it.join() }
    }
}