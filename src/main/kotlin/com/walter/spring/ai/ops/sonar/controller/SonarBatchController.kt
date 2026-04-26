package com.walter.spring.ai.ops.sonar.controller

import com.walter.spring.ai.ops.sonar.service.SonarScannerInstaller
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

/**
 * Mock SonarQube legacy batch endpoints for sonar-scanner-cli bootstrap.
 *
 * sonar-scanner-lib 3.x first tries GET /api/v2/analysis/engine (served by [SonarMockController]).
 * If that returns 404 it falls back to the legacy batch API at /batch/index + /batch/{filename}.
 * These endpoints serve the same sonar-scanner-engine-shaded JAR via the legacy text-index format
 * so that the fallback path also succeeds.
 *
 * Legacy batch protocol:
 *   GET /batch/index       → "filename.jar|md5hash\n"
 *   GET /batch/{filename}  → raw JAR bytes
 */
@Hidden
@RestController
class SonarBatchController(
    private val sonarScannerInstaller: SonarScannerInstaller,
) {

    @GetMapping("/batch/index", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun getBatchIndex(): String {
        val engine = sonarScannerInstaller.resolveOrInstallEngine()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "sonar-scanner engine not available")
        val md5 = computeMd5(engine.readBytes())
        return "${sonarScannerInstaller.engineFilename()}|$md5\n"
    }

    @GetMapping("/batch/{filename}")
    fun downloadBatchFile(@PathVariable filename: String, response: HttpServletResponse) {
        if (filename != sonarScannerInstaller.engineFilename()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Batch file '$filename' not found")
        }
        val engine = sonarScannerInstaller.resolveOrInstallEngine()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "sonar-scanner engine not available")
        response.contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE
        response.setContentLengthLong(engine.length())
        engine.inputStream().use { it.copyTo(response.outputStream) }
    }

    private fun computeMd5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}