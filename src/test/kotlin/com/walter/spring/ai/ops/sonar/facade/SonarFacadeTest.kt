package com.walter.spring.ai.ops.sonar.facade

import com.walter.spring.ai.ops.sonar.service.SonarAnalysisService
import com.walter.spring.ai.ops.sonar.service.SonarService
import com.walter.spring.ai.ops.sonar.service.dto.SonarIssue
import com.walter.spring.ai.ops.sonar.service.dto.SonarScanResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import java.nio.file.Path

@ExtendWith(MockitoExtension::class)
class SonarFacadeTest {

    @Mock private lateinit var sonarService: SonarService
    @Mock private lateinit var sonarAnalysisService: SonarAnalysisService

    private lateinit var sonarFacade: SonarFacade

    @BeforeEach
    fun setUp() {
        sonarFacade = SonarFacade(sonarService, sonarAnalysisService)
    }

    @Test
    @DisplayName("스캔 성공 시 SonarAnalysisService.extractIssues를 호출하고 결과를 반환한다")
    fun givenSuccessfulScan_whenAnalyzeAndGetIssues_thenReturnsExtractedIssues() {
        // given
        val projectPath = Path.of("/tmp/my-project")
        val expectedIssues = listOf(
            SonarIssue(ruleKey = "kotlin:S1234", severity = "MAJOR", component = "Main.kt", line = 10, message = "Fix this")
        )
        given(sonarService.analyze(projectPath)).willReturn(SonarScanResult("my-project", success = true))
        given(sonarAnalysisService.extractIssues("my-project")).willReturn(expectedIssues)

        // when
        val result = sonarFacade.analyzeAndGetIssues(projectPath)

        // then
        assertThat(result).isEqualTo(expectedIssues)
        verify(sonarAnalysisService).extractIssues("my-project")
    }

    @Test
    @DisplayName("스캔 실패 시 extractIssues를 호출하지 않고 빈 리스트를 반환한다")
    fun givenFailedScan_whenAnalyzeAndGetIssues_thenReturnsEmptyListWithoutExtraction() {
        // given
        val projectPath = Path.of("/tmp/my-project")
        given(sonarService.analyze(projectPath)).willReturn(SonarScanResult("my-project", success = false))

        // when
        val result = sonarFacade.analyzeAndGetIssues(projectPath)

        // then
        assertThat(result).isEmpty()
        verifyNoInteractions(sonarAnalysisService)
    }

    @Test
    @DisplayName("스캔 성공 후 extractIssues가 빈 리스트를 반환하면 빈 리스트를 그대로 반환한다")
    fun givenSuccessfulScanWithNoIssues_whenAnalyzeAndGetIssues_thenReturnsEmptyList() {
        // given
        val projectPath = Path.of("/tmp/my-project")
        given(sonarService.analyze(projectPath)).willReturn(SonarScanResult("my-project", success = true))
        given(sonarAnalysisService.extractIssues("my-project")).willReturn(emptyList())

        // when
        val result = sonarFacade.analyzeAndGetIssues(projectPath)

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("projectKey는 SonarService.analyze가 반환한 SonarScanResult에서 가져온다")
    fun givenScanResult_whenAnalyzeAndGetIssues_thenUsesProjectKeyFromScanResult() {
        // given
        val projectPath = Path.of("/workspace/repos/backend-service")
        given(sonarService.analyze(projectPath)).willReturn(SonarScanResult("backend-service", success = true))
        given(sonarAnalysisService.extractIssues("backend-service")).willReturn(emptyList())

        // when
        sonarFacade.analyzeAndGetIssues(projectPath)

        // then
        verify(sonarAnalysisService).extractIssues("backend-service")
    }

    @Test
    @DisplayName("SonarService가 예외를 던지면 빈 리스트를 반환하고 예외가 전파되지 않는다")
    fun givenSonarServiceThrows_whenAnalyzeAndGetIssues_thenReturnsEmptyListWithoutThrowing() {
        // given
        val projectPath = Path.of("/tmp/my-project")
        given(sonarService.analyze(projectPath)).willThrow(RuntimeException("sonar-scanner not found"))

        // when
        val result = sonarFacade.analyzeAndGetIssues(projectPath)

        // then
        assertThat(result).isEmpty()
        verifyNoInteractions(sonarAnalysisService)
    }
}
