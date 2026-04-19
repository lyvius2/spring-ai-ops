package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.record.CodeRiskRecord
import com.walter.spring.ai.ops.service.AiModelService
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.dto.CodeChunk
import com.walter.spring.ai.ops.service.RepositoryService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Facade
class CodeRiskAnalyzeFacade(
    private val repositoryService: RepositoryService,
    private val aiModelService: AiModelService,
    private val applicationService: ApplicationService,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(CodeRiskAnalyzeFacade::class.java)

    companion object {
        private const val TOKEN_THRESHOLD = 50_000
    }

    fun analyze(appName: String, branch: String): CodeRiskRecord {
        val gitRepoUrl = applicationService.getGitRepoByAppName(appName)
        val sourcePath = repositoryService.cloneRepository(appName, gitRepoUrl, branch)
        val files = repositoryService.collectSourceFiles(sourcePath)
        val bundle = repositoryService.buildBundle(sourcePath, files)
        val tokenCount = aiModelService.estimateTokenCount(bundle)
        log.info("Code risk analysis started — app: {}, estimated tokens: {}", appName, tokenCount)

        val result = if (tokenCount <= TOKEN_THRESHOLD) {
            log.info("Strategy: single-call analysis")
            aiModelService.executeAnalyzeCodeRisk(bundle)
        } else {
            log.info("Strategy: map-reduce analysis")
            val chunks = repositoryService.createChunks(sourcePath, files)
            val issueList = executeMapPhase(chunks)
            aiModelService.executeFinalAnalyzeCode(issueList)
        }
        return repositoryService.saveAnalyzedResult(appName, gitRepoUrl, branch, result)
    }

    fun getRecords(appName: String) = repositoryService.getCodeRiskRecords(appName)

    private fun executeMapPhase(chunks: List<CodeChunk>): List<String> {
        val futures = chunks.map { chunk ->
            CompletableFuture.supplyAsync({
                log.info("Analyzing chunk: {}", chunk.label)
                aiModelService.executeAnalyzeCodeRisk(chunk.bundle)
            }, executor)
        }
        return futures.map { it.join() }
    }
}
