package com.walter.spring.ai.ops.sonar.service

import com.walter.spring.ai.ops.sonar.service.dto.SonarScanResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Runs sonar-scanner against a local source directory and returns the scan outcome.
 *
 * The scanner binary is resolved (and auto-installed if needed) via [SonarScannerInstaller].
 * The scanner is pointed at the embedded Mock SonarQube API (sonar.host.url=http://localhost:{port}),
 * so no external SonarQube server is required. Once the scan completes, the submitted report
 * is available via SonarAnalysisService and can be retrieved by the calling Facade.
 */
@Service
class SonarService(
    private val scannerProcessExecutor: ScannerProcessExecutor,
    private val sonarScannerInstaller: SonarScannerInstaller,
    @Value("\${server.port:8080}") private val serverPort: Int,
) {
    private val log = LoggerFactory.getLogger(SonarService::class.java)

    fun analyze(projectPath: Path): SonarScanResult {
        val projectKey = projectPath.fileName.toString()
        log.info("Starting SonarQube analysis — project: {}, path: {}", projectKey, projectPath)

        val command = buildScannerCommand(projectPath, projectKey)
        val exitCode = scannerProcessExecutor.execute(command, projectPath.toFile())
        val success = exitCode == 0

        log.info("SonarQube analysis completed — project: {}, exitCode: {}, success: {}", projectKey, exitCode, success)
        return SonarScanResult(projectKey = projectKey, success = success)
    }

    private fun buildScannerCommand(projectPath: Path, projectKey: String): List<String> = listOf(
        sonarScannerInstaller.resolveOrInstall(),
        "-Dsonar.host.url=http://localhost:$serverPort",
        "-Dsonar.token=embedded",
        "-Dsonar.projectKey=$projectKey",
        "-Dsonar.sources=.",
        "-Dsonar.projectBaseDir=${projectPath.toAbsolutePath()}"
    )
}
