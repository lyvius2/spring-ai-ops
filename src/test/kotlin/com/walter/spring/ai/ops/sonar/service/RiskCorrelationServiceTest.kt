package com.walter.spring.ai.ops.sonar.service

import com.walter.spring.ai.ops.record.CodeRiskIssue
import com.walter.spring.ai.ops.sonar.service.dto.SonarIssue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RiskCorrelationServiceTest {

    private lateinit var service: RiskCorrelationService

    @BeforeEach
    fun setUp() {
        service = RiskCorrelationService()
    }

    // ── empty inputs ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("두 입력이 모두 비어있으면 빈 리스트를 반환한다")
    fun givenBothEmpty_whenCorrelate_thenReturnsEmptyList() {
        // when
        val result = service.correlate(emptyList(), emptyList())

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("SonarQube 이슈가 없으면 모든 LLM 이슈는 MEDIUM이 된다")
    fun givenNoSonarIssues_whenCorrelate_thenAllLlmIssuesMedium() {
        // given
        val llmIssues = listOf(
            llmIssue("src/Foo.kt", "10", "CRITICAL", "Null pointer"),
            llmIssue("src/Bar.kt", "20", "HIGH",     "SQL injection"),
        )

        // when
        val result = service.correlate(llmIssues, emptyList())

        // then
        assertThat(result).hasSize(2)
        assertThat(result.map { it.severity() }).containsOnly("MEDIUM")
    }

    @Test
    @DisplayName("LLM 이슈가 없으면 모든 SonarQube 이슈는 LOW가 된다")
    fun givenNoLlmIssues_whenCorrelate_thenAllSonarIssuesLow() {
        // given
        val sonarIssues = listOf(
            sonarIssue("kotlin:S101", "src/Foo.kt", 10, "Rename"),
            sonarIssue("kotlin:S102", "src/Bar.kt", 20, "Unused variable"),
        )

        // when
        val result = service.correlate(emptyList(), sonarIssues)

        // then
        assertThat(result).hasSize(2)
        assertThat(result.map { it.severity() }).containsOnly("LOW")
    }

    // ── severity assignment ───────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 파일과 가까운 라인의 LLM-SonarQube 이슈는 교집합으로 HIGH가 된다")
    fun givenMatchingIssues_whenCorrelate_thenMatchedIssueSeverityIsHigh() {
        // given
        val llmIssues = listOf(llmIssue("src/Foo.kt", "42", "CRITICAL", "NPE risk"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/Foo.kt", 45, "Null dereference"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then
        assertThat(result.first { it.file() == "src/Foo.kt" }.severity()).isEqualTo("HIGH")
    }

    @Test
    @DisplayName("같은 파일이어도 라인 차이가 허용 범위(10)를 초과하면 교집합으로 처리하지 않는다")
    fun givenSameFileButDistantLines_whenCorrelate_thenNotMatched() {
        // given
        val llmIssues = listOf(llmIssue("src/Foo.kt", "1", "HIGH", "Issue at top"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/Foo.kt", 20, "Issue at bottom"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then — LLM issue is unmatched → MEDIUM; Sonar issue appears as LOW
        assertThat(result).hasSize(2)
        assertThat(result.map { it.severity() }).containsExactlyInAnyOrder("MEDIUM", "LOW")
    }

    @Test
    @DisplayName("라인 차이가 정확히 허용 범위(10)이면 교집합으로 처리된다")
    fun givenLineExactlyAtTolerance_whenCorrelate_thenMatched() {
        // given
        val llmIssues = listOf(llmIssue("src/Foo.kt", "10", "HIGH", "Issue"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/Foo.kt", 20, "Issue"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then
        assertThat(result.filter { it.severity() == "HIGH" }).hasSize(1)
    }

    @Test
    @DisplayName("파일이 다르면 같은 라인이어도 교집합으로 처리하지 않는다")
    fun givenDifferentFiles_whenCorrelate_thenNotMatched() {
        // given
        val llmIssues = listOf(llmIssue("src/Foo.kt", "10", "HIGH", "Issue"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/Bar.kt", 10, "Issue"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then
        assertThat(result).hasSize(2)
        assertThat(result.map { it.severity() }).containsExactlyInAnyOrder("MEDIUM", "LOW")
    }

    @Test
    @DisplayName("LLM 이슈 file이 null이면 SonarQube와 매칭되지 않는다")
    fun givenLlmIssueWithNullFile_whenCorrelate_thenNotMatched() {
        // given
        val llmIssues = listOf(llmIssue(null, "10", "HIGH", "No file info"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/Foo.kt", 10, "Issue"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then
        assertThat(result).hasSize(2)
        assertThat(result.map { it.severity() }).containsExactlyInAnyOrder("MEDIUM", "LOW")
    }

    // ── file path matching ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM 파일명이 SonarQube의 경로 suffix와 일치하면 교집합으로 처리된다")
    fun givenPartialFilePathMatch_whenCorrelate_thenMatched() {
        // given — LLM gives a short path, Sonar gives a full path
        val llmIssues = listOf(llmIssue("Foo.kt", "10", "HIGH", "Issue"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/main/kotlin/Foo.kt", 10, "Issue"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then
        assertThat(result.filter { it.severity() == "HIGH" }).hasSize(1)
    }

    @Test
    @DisplayName("백슬래시 경로도 포워드슬래시로 정규화되어 매칭된다")
    fun givenWindowsStylePath_whenCorrelate_thenMatched() {
        // given
        val llmIssues = listOf(llmIssue("src\\main\\kotlin\\Foo.kt", "10", "HIGH", "Issue"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/main/kotlin/Foo.kt", 10, "Issue"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then
        assertThat(result.filter { it.severity() == "HIGH" }).hasSize(1)
    }

    // ── line parsing ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM 라인이 범위 형식(40-45)이면 첫 번째 값으로 파싱된다")
    fun givenLineRange_whenCorrelate_thenFirstValueUsedForMatching() {
        // given — LLM line "40-45", SonarQube line 42 → diff = 2 ≤ 10 → match
        val llmIssues = listOf(llmIssue("src/Foo.kt", "40-45", "HIGH", "Issue"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/Foo.kt", 42, "Issue"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then
        assertThat(result.filter { it.severity() == "HIGH" }).hasSize(1)
    }

    @Test
    @DisplayName("LLM 라인 정보가 없으면 파일 일치만으로 교집합으로 처리된다")
    fun givenLlmIssueWithNoLine_whenCorrelate_thenFileOnlyMatchSuffices() {
        // given
        val llmIssues = listOf(llmIssue("src/Foo.kt", null, "HIGH", "Issue"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/Foo.kt", 42, "Issue"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then
        assertThat(result.filter { it.severity() == "HIGH" }).hasSize(1)
    }

    @Test
    @DisplayName("SonarQube 라인 정보가 없으면 파일 일치만으로 교집합으로 처리된다")
    fun givenSonarIssueWithNoLine_whenCorrelate_thenFileOnlyMatchSuffices() {
        // given
        val llmIssues = listOf(llmIssue("src/Foo.kt", "42", "HIGH", "Issue"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/Foo.kt", null, "Issue"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then
        assertThat(result.filter { it.severity() == "HIGH" }).hasSize(1)
    }

    // ── SonarQube-only issue conversion ───────────────────────────────────────────

    @Test
    @DisplayName("SonarQube-only 이슈는 ruleKey와 message를 포함하는 description으로 변환된다")
    fun givenSonarOnlyIssue_whenCorrelate_thenDescriptionContainsRuleKeyAndMessage() {
        // given
        val sonarIssues = listOf(sonarIssue("kotlin:S1234", "src/Foo.kt", 10, "Avoid this pattern"))

        // when
        val result = service.correlate(emptyList(), sonarIssues)

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].description()).contains("kotlin:S1234")
        assertThat(result[0].description()).contains("Avoid this pattern")
        assertThat(result[0].severity()).isEqualTo("LOW")
    }

    @Test
    @DisplayName("하나의 SonarQube 이슈가 매칭된 경우 동일 이슈가 LOW로 중복 추가되지 않는다")
    fun givenMatchedSonarIssue_whenCorrelate_thenMatchedSonarIssueNotDuplicated() {
        // given
        val llmIssues = listOf(llmIssue("src/Foo.kt", "10", "CRITICAL", "Issue"))
        val sonarIssues = listOf(sonarIssue("kotlin:S101", "src/Foo.kt", 10, "Issue"))

        // when
        val result = service.correlate(llmIssues, sonarIssues)

        // then — only 1 issue (matched → HIGH), no LOW duplicate
        assertThat(result).hasSize(1)
        assertThat(result[0].severity()).isEqualTo("HIGH")
    }

    // ── test data builders ────────────────────────────────────────────────────────

    private fun llmIssue(file: String?, line: String?, severity: String, description: String): CodeRiskIssue =
        CodeRiskIssue(file, line, severity, description, "Fix it.", null)

    private fun sonarIssue(ruleKey: String, component: String, line: Int?, message: String): SonarIssue =
        SonarIssue(ruleKey, "MAJOR", component, line, message)
}