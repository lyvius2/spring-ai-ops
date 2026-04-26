package com.walter.spring.ai.ops.sonar.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SonarScannerInstallerTest {

    @TempDir
    private lateinit var tempDir: Path

    // ── resolveOrInstall ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("configuredPath가 설정되어 있으면 즉시 해당 경로를 반환한다")
    fun givenConfiguredPath_whenResolveOrInstall_thenReturnsConfiguredPath() {
        // given
        val installer = installer(configuredPath = "/usr/local/bin/sonar-scanner")

        // when
        val result = installer.resolveOrInstall()

        // then
        assertThat(result).isEqualTo("/usr/local/bin/sonar-scanner")
    }

    @Test
    @DisplayName("관리 바이너리가 이미 존재하면 다운로드 없이 해당 경로를 반환한다")
    fun givenManagedBinaryExists_whenResolveOrInstall_thenReturnsManagedPathWithoutDownload() {
        // given
        val version = "6.2.1.4610"
        val binaryFile = tempDir.resolve("sonar-scanner-$version/bin/sonar-scanner").toFile()
        binaryFile.parentFile.mkdirs()
        binaryFile.createNewFile()

        val installer = installer(installDir = tempDir.toString(), version = version)

        // when
        val result = installer.resolveOrInstall()

        // then
        assertThat(result).isEqualTo(binaryFile.absolutePath)
    }

    @Test
    @DisplayName("바이너리가 없으면 ZIP을 다운로드하고 압축 해제 후 경로를 반환한다")
    fun givenBinaryAbsent_whenResolveOrInstall_thenInstallsAndReturnsBinaryPath() {
        // given
        val version = "6.2.1.4610"
        val zip = buildFakeScannerZip(version)
        val installer = installer(installDir = tempDir.toString(), version = version, zipBytes = zip)

        // when
        val result = installer.resolveOrInstall()

        // then
        assertThat(result).endsWith("sonar-scanner-$version/bin/sonar-scanner")
        assertThat(java.io.File(result)).exists()
    }

    // ── isInstalled ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("configuredPath가 설정되어 있으면 isInstalled는 true를 반환한다")
    fun givenConfiguredPath_whenIsInstalled_thenReturnsTrue() {
        // given
        val installer = installer(configuredPath = "/some/path/sonar-scanner")

        // when / then
        assertThat(installer.isInstalled()).isTrue()
    }

    @Test
    @DisplayName("관리 바이너리가 존재하면 isInstalled는 true를 반환한다")
    fun givenManagedBinaryExists_whenIsInstalled_thenReturnsTrue() {
        // given
        val version = "6.2.1.4610"
        val binaryFile = tempDir.resolve("sonar-scanner-$version/bin/sonar-scanner").toFile()
        binaryFile.parentFile.mkdirs()
        binaryFile.createNewFile()

        val installer = installer(installDir = tempDir.toString(), version = version)

        // when / then
        assertThat(installer.isInstalled()).isTrue()
    }

    @Test
    @DisplayName("바이너리가 없고 configuredPath도 비어 있으면 isInstalled는 false를 반환한다")
    fun givenNoBinary_whenIsInstalled_thenReturnsFalse() {
        // given
        val installer = installer(installDir = tempDir.toString())

        // when / then
        assertThat(installer.isInstalled()).isFalse()
    }

    // ── install ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("install() 후 관리 바이너리 파일이 존재한다")
    fun givenValidZip_whenInstall_thenBinaryFileExists() {
        // given
        val version = "6.2.1.4610"
        val zip = buildFakeScannerZip(version)
        val installer = installer(installDir = tempDir.toString(), version = version, zipBytes = zip)

        // when
        installer.install()

        // then
        val binary = tempDir.resolve("sonar-scanner-$version/bin/sonar-scanner").toFile()
        assertThat(binary).exists()
    }

    @Test
    @DisplayName("install() 후 바이너리 파일에 실행 권한이 설정된다")
    fun givenValidZip_whenInstall_thenBinaryIsExecutable() {
        // given
        val version = "6.2.1.4610"
        val zip = buildFakeScannerZip(version)
        val installer = installer(installDir = tempDir.toString(), version = version, zipBytes = zip)

        // when
        installer.install()

        // then
        val binary = tempDir.resolve("sonar-scanner-$version/bin/sonar-scanner").toFile()
        assertThat(binary.canExecute()).isTrue()
    }

    // ── resolveOrInstallEngine ────────────────────────────────────────────────────

    @Test
    @DisplayName("엔진 파일이 이미 존재하면 다운로드 없이 해당 File을 반환한다")
    fun givenEngineExists_whenResolveOrInstallEngine_thenReturnsExistingFile() {
        // given
        val engineVersion = "10.3.0.82913"
        val engineFile = tempDir.resolve("engine/sonar-scanner-engine-shaded-$engineVersion.jar").toFile()
        engineFile.parentFile.mkdirs()
        engineFile.writeBytes(byteArrayOf(1, 2, 3))

        val installer = installer(installDir = tempDir.toString(), engineVersion = engineVersion)

        // when
        val result = installer.resolveOrInstallEngine()

        // then
        assertThat(result).isNotNull
        assertThat(result!!.absolutePath).isEqualTo(engineFile.absolutePath)
    }

    @Test
    @DisplayName("엔진 파일이 없으면 배포 ZIP에서 추출하여 해당 File을 반환한다")
    fun givenEngineAbsent_whenResolveOrInstallEngine_thenDownloadsAndReturnsFile() {
        // given
        val engineVersion = "10.3.0.82913"
        val fakeEngine = buildFakeEngineJar()
        val installer = installer(installDir = tempDir.toString(), engineVersion = engineVersion, engineBytes = fakeEngine)

        // when
        val result = installer.resolveOrInstallEngine()

        // then
        assertThat(result).isNotNull
        assertThat(result!!.name).isEqualTo("sonar-scanner-engine-shaded-$engineVersion.jar")
        assertThat(result.readBytes()).isEqualTo(fakeEngine)
    }

    @Test
    @DisplayName("fetchEngineFromDistribution이 예외를 던지면 resolveOrInstallEngine은 null을 반환한다")
    fun givenEngineFetchFails_whenResolveOrInstallEngine_thenReturnsNull() {
        // given
        val installer = object : SonarScannerInstaller("", "6.2.1.4610", tempDir.toString(), "10.3.0.82913") {
            override fun fetchZip(url: String): ByteArray = byteArrayOf()
            override fun fetchEngineFromDistribution(version: String): ByteArray =
                throw RuntimeException("download failed")
        }

        // when
        val result = installer.resolveOrInstallEngine()

        // then
        assertThat(result).isNull()
    }

    @Test
    @DisplayName("engineFilename은 버전 정보를 포함한 JAR 파일명을 반환한다")
    fun givenEngineVersion_whenEngineFilename_thenReturnsVersionedFilename() {
        // given
        val installer = installer(engineVersion = "10.3.0.82913")

        // when / then
        assertThat(installer.engineFilename()).isEqualTo("sonar-scanner-engine-shaded-10.3.0.82913.jar")
    }

    // ── test helpers ──────────────────────────────────────────────────────────────

    /**
     * Creates a [SonarScannerInstaller] that overrides [fetchZip] and [fetchEngineFromDistribution]
     * to return in-memory bytes instead of hitting the network.
     */
    private fun installer(
        configuredPath: String = "",
        version: String = "6.2.1.4610",
        installDir: String = tempDir.toString(),
        engineVersion: String = "10.3.0.82913",
        zipBytes: ByteArray = byteArrayOf(),
        engineBytes: ByteArray = buildFakeEngineJar(),
    ): SonarScannerInstaller = object : SonarScannerInstaller(configuredPath, version, installDir, engineVersion) {
        override fun fetchZip(url: String): ByteArray = zipBytes
        override fun fetchEngineFromDistribution(version: String): ByteArray = engineBytes
    }

    // ── test data helpers ─────────────────────────────────────────────────────────

    /**
     * Builds a minimal ZIP that mimics the sonar-scanner-cli layout:
     *   sonar-scanner-{version}/bin/sonar-scanner
     */
    private fun buildFakeScannerZip(version: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("sonar-scanner-$version/bin/sonar-scanner"))
            zip.write("#!/bin/sh\necho sonar-scanner".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** Returns minimal fake engine JAR bytes (raw bytes, not a ZIP). */
    private fun buildFakeEngineJar(): ByteArray =
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
}