package com.walter.spring.ai.ops.facade

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.record.CodeRiskIssue
import com.walter.spring.ai.ops.record.CodeRiskRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.dto.CodeChunk
import com.walter.spring.ai.ops.service.RepositoryService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore

@Facade
class CodeRiskAnalyzeFacade(
    private val repositoryService: RepositoryService,
    private val aiModelService: AiModelService,
    private val applicationService: ApplicationService,
    private val githubService: GithubService,
    private val gitlabService: GitlabService,
    private val objectMapper: ObjectMapper,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
    @Value("\${analysis.code-risk.token-threshold:27000}") private val tokenThreshold: Int,
    @Value("\${analysis.code-risk.map-reduce-concurrency:3}") private val mapReduceConcurrency: Int,
    @Value("\${analysis.code-risk.map-reduce-delay-ms:1000}") private val mapReduceDelayMs: Long,
) {
    private val log = LoggerFactory.getLogger(CodeRiskAnalyzeFacade::class.java)
    private val lenientMapper: ObjectMapper = objectMapper.copy().apply {
        configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
        configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
    }

    companion object {
        private const val ISSUES_START = "---ISSUES_JSON_START---"
        private const val ISSUES_END   = "---ISSUES_JSON_END---"
    }

    fun analyze(appName: String, branch: String): CodeRiskRecord {
        val gitRepoUrl = applicationService.getGitRepoByAppName(appName)
        val accessToken = resolveAccessToken(gitRepoUrl)
        val sourcePath = repositoryService.cloneRepository(appName, gitRepoUrl, branch, accessToken)
        val files = repositoryService.collectSourceFiles(sourcePath)
        val bundle = repositoryService.buildBundle(sourcePath, files)
        val tokenCount = aiModelService.estimateTokenCount(bundle)
        log.info("Code risk analysis started — app: {}, estimated tokens: {}", appName, tokenCount)

        val (markdown, issues) = if (tokenCount <= tokenThreshold) {
            log.info("Strategy: single-call analysis")
            val raw = aiModelService.executeAnalyzeCodeRisk(bundle)
            parseResponse(raw)
        } else {
            log.info("Strategy: map-reduce analysis")
            val chunks = repositoryService.createChunks(sourcePath, files)
            val rawResults = executeMapPhase(chunks)
            val parsedChunks = rawResults.map { parseResponse(it) }
            val markdownParts = parsedChunks.map { it.first }
            val allIssues = parsedChunks.flatMap { it.second }
            val finalMarkdown = aiModelService.executeFinalAnalyzeCode(markdownParts)
            Pair(finalMarkdown, allIssues)
        }

        return repositoryService.saveAnalyzedResult(appName, gitRepoUrl, branch, markdown, issues)
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
        if (startIdx == -1) return Pair(raw.trim(), emptyList())

        val markdown = raw.substring(0, startIdx).trim()
        val afterStart = raw.substring(startIdx + ISSUES_START.length)
        val endIdx = afterStart.indexOf(ISSUES_END)
        val jsonText = (if (endIdx == -1) afterStart else afterStart.substring(0, endIdx)).trim()

        val issues = runCatching {
            lenientMapper.readValue(jsonText, Array<CodeRiskIssue>::class.java).toList()
        }.getOrElse {
            log.warn("Failed to parse issues JSON: {}", it.message)
            emptyList()
        }
        return Pair(markdown, issues)
    }

    private fun executeMapPhase(chunks: List<CodeChunk>): List<String> {
        val semaphore = Semaphore(mapReduceConcurrency)
        val futures = chunks.map { chunk ->
            CompletableFuture.supplyAsync({
                semaphore.acquire()
                try {
                    log.info("analyzing chunk: {}", chunk.label)
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
