package com.walter.spring.ai.ops.sonar.facade

import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.sonar.service.SonarAnalysisService
import com.walter.spring.ai.ops.sonar.service.SonarService
import com.walter.spring.ai.ops.sonar.service.dto.SonarIssue
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Orchestrates the full SonarQube embedded analysis pipeline.
 *
 * Flow:
 *   1. SonarService.analyze(path)       — runs sonar-scanner subprocess → Mock API → report submitted
 *   2. SonarAnalysisService.extractIssues(projectKey) — parses the stored report ZIP → List<SonarIssue>
 */
@Facade
class SonarFacade(
    private val sonarService: SonarService,
    private val sonarAnalysisService: SonarAnalysisService,
) {
    private val log = LoggerFactory.getLogger(SonarFacade::class.java)

    fun analyzeAndGetIssues(projectPath: Path): List<SonarIssue> {
        return try {
            val scanResult = sonarService.analyze(projectPath)

            if (!scanResult.success) {
                log.warn("SonarQube scan failed — project: {}, skipping issue extraction", scanResult.projectKey)
                return emptyList()
            }

            sonarAnalysisService.extractIssues(scanResult.projectKey)
        } catch (e: Exception) {
            log.warn("SonarQube analysis unavailable for '{}': {}", projectPath.fileName, e.message)
            emptyList()
        }
    }
}
