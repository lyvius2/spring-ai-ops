package com.walter.spring.ai.ops.sonar.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.BDDMockito.given
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import java.io.File
import java.nio.file.Path

@ExtendWith(MockitoExtension::class)
class SonarServiceTest {

    @Mock private lateinit var scannerProcessExecutor: ScannerProcessExecutor
    @Mock private lateinit var sonarScannerInstaller: SonarScannerInstaller
    @Captor private lateinit var commandCaptor: ArgumentCaptor<List<String>>
    @Captor private lateinit var workDirCaptor: ArgumentCaptor<File>

    private lateinit var sonarService: SonarService

    @BeforeEach
    fun setUp() {
        given(sonarScannerInstaller.resolveOrInstall()).willReturn("sonar-scanner")
        sonarService = SonarService(scannerProcessExecutor, sonarScannerInstaller, 7079)
    }

    @Test
    @DisplayName("sonar-scanner가 0을 반환하면 success=true인 SonarScanResult를 반환한다")
    fun givenScannerExitsWithZero_whenAnalyze_thenReturnsSuccessResult() {
        // given
        val projectPath = Path.of("/tmp/my-project")
        given(scannerProcessExecutor.execute(anyList(), anyFile())).willReturn(0)

        // when
        val result = sonarService.analyze(projectPath)

        // then
        assertThat(result.projectKey).isEqualTo("my-project")
        assertThat(result.success).isTrue()
    }

    @Test
    @DisplayName("sonar-scanner가 0이 아닌 코드를 반환하면 success=false인 SonarScanResult를 반환한다")
    fun givenScannerExitsWithNonZero_whenAnalyze_thenReturnsFailureResult() {
        // given
        val projectPath = Path.of("/tmp/my-project")
        given(scannerProcessExecutor.execute(anyList(), anyFile())).willReturn(1)

        // when
        val result = sonarService.analyze(projectPath)

        // then
        assertThat(result.projectKey).isEqualTo("my-project")
        assertThat(result.success).isFalse()
    }

    @Test
    @DisplayName("projectKey는 Path의 마지막 경로 요소(파일명)에서 파생된다")
    fun givenNestedPath_whenAnalyze_thenProjectKeyIsLastPathSegment() {
        // given
        val projectPath = Path.of("/workspace/repos/backend-service")
        given(scannerProcessExecutor.execute(anyList(), anyFile())).willReturn(0)

        // when
        val result = sonarService.analyze(projectPath)

        // then
        assertThat(result.projectKey).isEqualTo("backend-service")
    }

    @Test
    @DisplayName("scanner 명령에 sonar.host.url, sonar.projectKey, sonar.projectBaseDir가 포함된다")
    fun givenValidPath_whenAnalyze_thenCommandContainsRequiredSonarProperties() {
        // given
        val projectPath = Path.of("/tmp/test-app")
        given(scannerProcessExecutor.execute(anyList(), anyFile())).willReturn(0)

        // when
        sonarService.analyze(projectPath)

        // then
        Mockito.verify(scannerProcessExecutor).execute(captureList(), captureFile())
        val command = commandCaptor.value
        assertThat(command).anyMatch { it == "-Dsonar.host.url=http://localhost:7079" }
        assertThat(command).anyMatch { it == "-Dsonar.projectKey=test-app" }
        assertThat(command).anyMatch { it.startsWith("-Dsonar.projectBaseDir=") }
    }

    @Test
    @DisplayName("scanner 실행 시 작업 디렉토리는 projectPath와 동일한 디렉토리이다")
    fun givenValidPath_whenAnalyze_thenWorkDirMatchesProjectPath() {
        // given
        val projectPath = Path.of("/tmp/test-app")
        given(scannerProcessExecutor.execute(anyList(), anyFile())).willReturn(0)

        // when
        sonarService.analyze(projectPath)

        // then
        Mockito.verify(scannerProcessExecutor).execute(captureList(), captureFile())
        assertThat(workDirCaptor.value).isEqualTo(projectPath.toFile())
    }

    @Test
    @DisplayName("installer가 반환하는 경로가 명령의 첫 번째 요소로 사용된다")
    fun givenCustomScannerPath_whenAnalyze_thenCommandStartsWithScannerPath() {
        // given
        val customScannerPath = "/usr/local/bin/sonar-scanner"
        given(sonarScannerInstaller.resolveOrInstall()).willReturn(customScannerPath)
        given(scannerProcessExecutor.execute(anyList(), anyFile())).willReturn(0)

        // when
        sonarService.analyze(Path.of("/tmp/test-app"))

        // then
        Mockito.verify(scannerProcessExecutor).execute(captureList(), captureFile())
        assertThat(commandCaptor.value.first()).isEqualTo(customScannerPath)
    }

    // Kotlin null-safety helpers: Mockito matchers and captors return null, but Kotlin non-nullable params reject null.
    // The ?: fallback is never actually used — Mockito registers the matcher/captor before the fallback is evaluated.
    private fun anyList(): List<String> = Mockito.anyList() ?: emptyList()
    private fun anyFile(): File = Mockito.any(File::class.java) ?: File(".")
    private fun captureList(): List<String> = commandCaptor.capture() ?: emptyList()
    private fun captureFile(): File = workDirCaptor.capture() ?: File(".")
}
