package com.walter.spring.ai.ops.sonar.controller

import com.walter.spring.ai.ops.sonar.controller.dto.SonarAuthValidateResponse
import com.walter.spring.ai.ops.sonar.controller.dto.SonarCeSubmitResponse
import com.walter.spring.ai.ops.sonar.controller.dto.SonarCeTask
import com.walter.spring.ai.ops.sonar.controller.dto.SonarCeTaskResponse
import com.walter.spring.ai.ops.sonar.controller.dto.SonarPlugin
import com.walter.spring.ai.ops.sonar.controller.dto.SonarPluginsResponse
import com.walter.spring.ai.ops.sonar.controller.dto.SonarQualityProfile
import com.walter.spring.ai.ops.sonar.controller.dto.SonarQualityProfilesResponse
import com.walter.spring.ai.ops.sonar.controller.dto.SonarRulesResponse
import com.walter.spring.ai.ops.sonar.controller.dto.SonarSettingsResponse
import com.walter.spring.ai.ops.sonar.service.SonarAnalysisService
import com.walter.spring.ai.ops.sonar.service.SonarPluginRegistry
import com.walter.spring.ai.ops.sonar.service.SonarScannerInstaller
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Mock SonarQube REST API endpoints for embedded scanner operation.
 *
 * sonar-scanner performs three phases (Bootstrap → Analysis → Submit), each requiring
 * specific REST calls. This controller satisfies those calls so the scanner can run
 * self-contained against the Spring Boot app itself (sonar.host.url=http://localhost:{port}).
 *
 * Phase breakdown:
 *   Bootstrap : /api/server/version, /api/settings/values, /api/authentication/validate
 *   Analysis  : /api/qualityprofiles/search, /api/rules/search, /api/plugins/installed,
 *               /api/plugins/download
 *   Submit    : POST /api/ce/submit  →  GET /api/ce/task (SUCCESS polling)
 */
@Hidden
@Tag(name = "SonarQube Mock API", description = "Embedded Mock SonarQube server endpoints for sonar-scanner-engine integration.")
@RestController
@RequestMapping("/api")
class SonarMockController(
    private val sonarAnalysisService: SonarAnalysisService,
    private val sonarPluginRegistry: SonarPluginRegistry,
    private val sonarScannerInstaller: SonarScannerInstaller,
) {

    companion object {
        private val UPDATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
            .withZone(ZoneOffset.UTC)
            .format(Instant.EPOCH)

        /** Language keys supported by the embedded plugin set. */
        private val SUPPORTED_LANGUAGES = listOf(
            Triple("kotlin", "Kotlin",     "kotlin"),
            Triple("java",   "Java",       "java"),
            Triple("js",     "JavaScript", "javascript"),
            Triple("py",     "Python",     "python"),
            Triple("ruby",   "Ruby",       "ruby")
        )
    }

    // ── Bootstrap ──────────────────────────────────────────────────────────────

    @Operation(summary = "Bootstrap: server version")
    @GetMapping("/server/version", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun getServerVersion(): String = "10.3.0.1951"

    @Operation(summary = "Bootstrap: settings values")
    @GetMapping("/settings/values")
    fun getSettingsValues(): SonarSettingsResponse = SonarSettingsResponse()

    @Operation(summary = "Bootstrap: authentication validation")
    @GetMapping("/authentication/validate")
    fun validateAuthentication(): SonarAuthValidateResponse = SonarAuthValidateResponse()

    /**
     * Engine bootstrap (sonar-scanner-lib v2 API, SonarQube 10.4+).
     *
     * sonar-scanner-cli 6.x first calls GET /api/v2/analysis/engine to download the analysis
     * engine JAR before it can run any analysis. This endpoint resolves (and downloads if absent)
     * the sonar-scanner-engine-shaded JAR from Maven Central, then streams it back so the scanner
     * can cache it locally and bootstrap the analysis engine.
     */
    @Operation(summary = "Engine bootstrap: download sonar-scanner-engine-shaded JAR (v2 API)")
    @GetMapping("/v2/analysis/engine", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun downloadAnalysisEngine(response: HttpServletResponse) {
        val engineFile = sonarScannerInstaller.resolveOrInstallEngine()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,
                "sonar-scanner engine not available — check logs for download errors")
        response.contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE
        response.setHeader("Content-Disposition", "attachment; filename=\"${sonarScannerInstaller.engineFilename()}\"")
        response.setContentLengthLong(engineFile.length())
        engineFile.inputStream().use { it.copyTo(response.outputStream) }
    }

    // ── Analysis ───────────────────────────────────────────────────────────────

    @Operation(summary = "Analysis: installed plugins — returns all language plugins discovered on classpath")
    @GetMapping("/plugins/installed")
    fun getInstalledPlugins(): SonarPluginsResponse {
        val plugins = sonarPluginRegistry.getAll().map { info ->
            SonarPlugin(
                key       = info.key,
                name      = info.name,
                version   = info.version,
                filename  = info.filename,
                hash      = info.hash,
                updatedAt = UPDATED_AT
            )
        }
        return SonarPluginsResponse(plugins = plugins)
    }

    @Operation(summary = "Analysis: plugin JAR download")
    @GetMapping("/plugins/download")
    fun downloadPlugin(@RequestParam("plugin") key: String, response: HttpServletResponse) {
        val info = sonarPluginRegistry.get(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin '$key' not found")
        response.contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE
        response.setContentLengthLong(info.bytes.size.toLong())
        response.outputStream.write(info.bytes)
    }

    @Operation(summary = "Analysis: quality profiles for all supported languages")
    @GetMapping("/qualityprofiles/search")
    fun searchQualityProfiles(): SonarQualityProfilesResponse {
        val profiles = SUPPORTED_LANGUAGES.map { (languageKey, languageName, profileKeySuffix) ->
            SonarQualityProfile(
                key             = "embedded-$profileKeySuffix",
                name            = "Sonar way",
                language        = languageKey,
                languageName    = languageName,
                isDefault       = true,
                activeRuleCount = 0
            )
        }
        return SonarQualityProfilesResponse(profiles = profiles)
    }

    @Operation(summary = "Analysis: rules")
    @GetMapping("/rules/search")
    fun searchRules(): SonarRulesResponse = SonarRulesResponse(
        total = 0,
        p     = 1,
        ps    = 500,
        rules = emptyList()
    )

    // ── Submit ─────────────────────────────────────────────────────────────────

    @Operation(summary = "Submit: analysis report")
    @PostMapping("/ce/submit", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submitAnalysis(@RequestParam("projectKey") projectKey: String, @RequestPart("report") report: MultipartFile): SonarCeSubmitResponse {
        val taskId = "embedded-task-${System.currentTimeMillis()}"
        sonarAnalysisService.storeReport(projectKey, report.bytes)
        sonarAnalysisService.registerTask(taskId, projectKey)
        return SonarCeSubmitResponse(taskId = taskId, projectId = projectKey)
    }

    @Operation(summary = "Submit: task status polling")
    @GetMapping("/ce/task")
    fun getTaskStatus(@RequestParam("id") taskId: String): SonarCeTaskResponse {
        val projectKey = sonarAnalysisService.getProjectKeyForTask(taskId) ?: ""
        return SonarCeTaskResponse(
            task = SonarCeTask(id = taskId, type = "REPORT", status = "SUCCESS", projectId = projectKey)
        )
    }
}
