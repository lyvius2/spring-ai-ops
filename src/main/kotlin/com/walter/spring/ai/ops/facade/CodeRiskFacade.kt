package com.walter.spring.ai.ops.facade

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.record.CodeRiskIssue
import com.walter.spring.ai.ops.record.CodeRiskRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.dto.CodeChunk
import com.walter.spring.ai.ops.service.RepositoryService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore

@Facade
class CodeRiskFacade(
    private val repositoryService: RepositoryService,
    private val aiModelService: AiModelService,
    private val applicationService: ApplicationService,
    private val githubService: GithubService,
    private val gitlabService: GitlabService,
    private val messageService: MessageService,
    private val objectMapper: ObjectMapper,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
    @Value("\${analysis.code-risk.token-threshold:27000}") private val tokenThreshold: Int,
    @Value("\${analysis.code-risk.map-reduce-concurrency:3}") private val mapReduceConcurrency: Int,
    @Value("\${analysis.code-risk.map-reduce-delay-ms:1000}") private val mapReduceDelayMs: Long,
) {
    private val log = LoggerFactory.getLogger(CodeRiskFacade::class.java)
    private val lenientMapper: ObjectMapper = objectMapper.copy().apply {
        configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
        configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
    }

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

        CompletableFuture.runAsync( {
            val (markdown, issues) = executeAnalyze(tokenCount, bundle, files, sourcePath)
            messageService.pushAnalysisStatus("Analysis complete. Saving results...")
            val record = repositoryService.saveAnalyzedResult(appName, gitRepoUrl, branch, markdown, issues)
            messageService.pushAnalysisResult(record)
        }, executor).exceptionally { ex ->
            log.error("Code risk analysis failed for app: {}, error: {}", appName, ex.message)
            messageService.pushAnalysisResult(CodeRiskRecord.failure(appName, gitRepoUrl, branch, ex.message))
            null
        }
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
        if (startIdx == -1) return Pair(raw.trim(), emptyList())

        val markdown = raw.substring(0, startIdx).trim()
        val afterStart = raw.substring(startIdx + ISSUES_START.length)
        val endIdx = afterStart.indexOf(ISSUES_END)
        val jsonText = (if (endIdx == -1) afterStart else afterStart.substring(0, endIdx)).trim()
        val sanitized = sanitizeControlChars(jsonText)

        val issues = runCatching {
            lenientMapper.readValue(sanitized, Array<CodeRiskIssue>::class.java).toList()
        }.getOrElse {
            log.warn("Failed to parse issues JSON — attempting recovery: {}", it.message)
            val recovered = recoverIssuesFromJson(sanitized)
            if (recovered.isEmpty()) {
                messageService.pushAnalysisStatus("⚠ Some issue data could not be parsed — continuing with partial results.")
            } else {
                log.info("Recovered {} issue(s) from malformed JSON", recovered.size)
            }
            recovered
        }
        return Pair(markdown, issues)
    }

    private fun sanitizeControlChars(jsonText: String): String {
        val sb = StringBuilder(jsonText.length)
        var inString = false
        var escape = false
        for (c in jsonText) {
            when {
                escape           -> { sb.append(c); escape = false }
                c == '\\'        -> { sb.append(c); escape = true }
                c == '"'         -> { sb.append(c); inString = !inString }
                inString && c == '\n' -> sb.append("\\n")
                inString && c == '\r' -> sb.append("\\r")
                inString && c == '\t' -> sb.append("\\t")
                inString && c.code < 0x20 ->
                    sb.append("\\u${c.code.toString(16).padStart(4, '0')}")
                else             -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun recoverIssuesFromJson(jsonText: String): List<CodeRiskIssue> {
        val recovered = mutableListOf<CodeRiskIssue>()
        try {
            lenientMapper.createParser(jsonText).use { parser ->
                var token = parser.nextToken()
                if (token == JsonToken.START_ARRAY) token = parser.nextToken()
                while (token == JsonToken.START_OBJECT) {
                    runCatching {
                        val node = lenientMapper.readTree<JsonNode>(parser)
                        recovered.add(lenientMapper.treeToValue(node, CodeRiskIssue::class.java))
                    }
                    token = try { parser.nextToken() } catch (_: Exception) { break }
                }
            }
        } catch (_: Exception) { /* ignore outer parse errors */ }
        return recovered
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