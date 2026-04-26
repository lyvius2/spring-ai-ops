package com.walter.spring.ai.ops.sonar.service

import com.google.protobuf.CodedOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SonarAnalysisServiceTest {

    private lateinit var sonarAnalysisService: SonarAnalysisService

    @BeforeEach
    fun setUp() {
        sonarAnalysisService = SonarAnalysisService()
    }

    // ── report storage ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("리포트가 없을 때 getLatestReport는 null을 반환한다")
    fun givenNoReport_whenGetLatestReport_thenReturnsNull() {
        // when
        val result = sonarAnalysisService.getLatestReport("unknown-project")

        // then
        assertNull(result)
    }

    @Test
    @DisplayName("리포트가 없을 때 hasReport는 false를 반환한다")
    fun givenNoReport_whenHasReport_thenReturnsFalse() {
        // when
        val result = sonarAnalysisService.hasReport("unknown-project")

        // then
        assertFalse(result)
    }

    @Test
    @DisplayName("리포트를 저장하면 getLatestReport로 동일한 내용을 반환한다")
    fun givenStoredReport_whenGetLatestReport_thenReturnsStoredBytes() {
        // given
        val projectKey = "my-project"
        val reportBytes = "fake-report-content".toByteArray()

        // when
        sonarAnalysisService.storeReport(projectKey, reportBytes)
        val result = sonarAnalysisService.getLatestReport(projectKey)

        // then
        assertArrayEquals(reportBytes, result)
    }

    @Test
    @DisplayName("리포트를 저장하면 hasReport는 true를 반환한다")
    fun givenStoredReport_whenHasReport_thenReturnsTrue() {
        // given
        sonarAnalysisService.storeReport("my-project", byteArrayOf(1, 2, 3))

        // when
        val result = sonarAnalysisService.hasReport("my-project")

        // then
        assertTrue(result)
    }

    @Test
    @DisplayName("같은 projectKey로 저장 시 최신 리포트로 덮어쓴다")
    fun givenExistingReport_whenStoreReport_thenOverwritesWithLatest() {
        // given
        sonarAnalysisService.storeReport("my-project", "first-report".toByteArray())

        // when
        sonarAnalysisService.storeReport("my-project", "second-report".toByteArray())
        val result = sonarAnalysisService.getLatestReport("my-project")

        // then
        assertArrayEquals("second-report".toByteArray(), result)
    }

    @Test
    @DisplayName("서로 다른 projectKey의 리포트는 독립적으로 저장된다")
    fun givenMultipleProjects_whenStoreReport_thenStoredIndependently() {
        // given
        val reportA = "report-a".toByteArray()
        val reportB = "report-b".toByteArray()

        // when
        sonarAnalysisService.storeReport("project-a", reportA)
        sonarAnalysisService.storeReport("project-b", reportB)

        // then
        assertArrayEquals(reportA, sonarAnalysisService.getLatestReport("project-a"))
        assertArrayEquals(reportB, sonarAnalysisService.getLatestReport("project-b"))
    }

    // ── task tracking ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("registerTask로 등록한 taskId로 projectKey를 조회할 수 있다")
    fun givenRegisteredTask_whenGetProjectKeyForTask_thenReturnsProjectKey() {
        // given
        sonarAnalysisService.registerTask("task-001", "my-project")

        // when
        val result = sonarAnalysisService.getProjectKeyForTask("task-001")

        // then
        assertThat(result).isEqualTo("my-project")
    }

    @Test
    @DisplayName("등록되지 않은 taskId 조회 시 null을 반환한다")
    fun givenUnknownTaskId_whenGetProjectKeyForTask_thenReturnsNull() {
        // when
        val result = sonarAnalysisService.getProjectKeyForTask("unknown-task")

        // then
        assertNull(result)
    }

    @Test
    @DisplayName("동일한 taskId를 재등록하면 최신 projectKey로 덮어쓴다")
    fun givenExistingTask_whenRegisterTask_thenOverwritesProjectKey() {
        // given
        sonarAnalysisService.registerTask("task-001", "old-project")

        // when
        sonarAnalysisService.registerTask("task-001", "new-project")

        // then
        assertThat(sonarAnalysisService.getProjectKeyForTask("task-001")).isEqualTo("new-project")
    }

    // ── issue extraction: empty / error cases ─────────────────────────────────────

    @Test
    @DisplayName("리포트가 없을 때 extractIssues는 빈 리스트를 반환한다")
    fun givenNoReport_whenExtractIssues_thenReturnsEmptyList() {
        // when
        val result = sonarAnalysisService.extractIssues("unknown-project")

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("issues 파일이 없는 ZIP 리포트는 빈 리스트를 반환한다")
    fun givenZipWithNoIssueFiles_whenExtractIssues_thenReturnsEmptyList() {
        // given
        sonarAnalysisService.storeReport("my-project", buildZip("metadata.pb" to "stub".toByteArray()))

        // when
        val result = sonarAnalysisService.extractIssues("my-project")

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("손상된 리포트 바이트는 예외 없이 빈 리스트를 반환한다")
    fun givenCorruptedReportBytes_whenExtractIssues_thenReturnsEmptyListWithoutException() {
        // given
        sonarAnalysisService.storeReport("my-project", byteArrayOf(0xFF.toByte(), 0xFE.toByte()))

        // when
        val result = sonarAnalysisService.extractIssues("my-project")

        // then
        assertThat(result).isEmpty()
    }

    // ── issue extraction: protobuf parsing ────────────────────────────────────────

    @Test
    @DisplayName("issues-1.pb에 인코딩된 Issue를 파싱하여 SonarIssue로 반환한다")
    fun givenIssueFile_whenExtractIssues_thenReturnsParsedIssue() {
        // given
        val issueBytes = buildIssueMessage(ruleKey = "kotlin:S1234", line = 42, msg = "Avoid this pattern", severity = 3 /* MAJOR */)
        val zipBytes = buildZip("issues-1.pb" to buildDelimitedIssueFile(issueBytes))
        sonarAnalysisService.storeReport("my-project", zipBytes)

        // when
        val result = sonarAnalysisService.extractIssues("my-project")

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].ruleKey).isEqualTo("kotlin:S1234")
        assertThat(result[0].line).isEqualTo(42)
        assertThat(result[0].message).isEqualTo("Avoid this pattern")
        assertThat(result[0].severity).isEqualTo("MAJOR")
    }

    @Test
    @DisplayName("component-1.pb의 projectRelativePath가 이슈의 component 필드에 반영된다")
    fun givenComponentAndIssueFile_whenExtractIssues_thenComponentPathIsPopulated() {
        // given
        val issueBytes = buildIssueMessage(ruleKey = "kotlin:S101", line = 5, msg = "Rename", severity = 2 /* MINOR */)
        val componentBytes = buildComponentMessage(projectRelativePath = "src/main/kotlin/Foo.kt")
        val zipBytes = buildZip(
            "issues-1.pb"    to buildDelimitedIssueFile(issueBytes),
            "component-1.pb" to componentBytes
        )
        sonarAnalysisService.storeReport("my-project", zipBytes)

        // when
        val result = sonarAnalysisService.extractIssues("my-project")

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].component).isEqualTo("src/main/kotlin/Foo.kt")
    }

    @Test
    @DisplayName("여러 issues-N.pb 파일의 이슈를 모두 합산하여 반환한다")
    fun givenMultipleIssueFiles_whenExtractIssues_thenReturnsAllIssues() {
        // given
        val issue1 = buildIssueMessage("kotlin:S1", 10, "Issue in file 1", 3)
        val issue2 = buildIssueMessage("kotlin:S2", 20, "Issue in file 2", 4)
        val zipBytes = buildZip(
            "issues-1.pb" to buildDelimitedIssueFile(issue1),
            "issues-2.pb" to buildDelimitedIssueFile(issue2)
        )
        sonarAnalysisService.storeReport("my-project", zipBytes)

        // when
        val result = sonarAnalysisService.extractIssues("my-project")

        // then
        assertThat(result).hasSize(2)
        assertThat(result.map { it.ruleKey }).containsExactlyInAnyOrder("kotlin:S1", "kotlin:S2")
    }

    @Test
    @DisplayName("동일한 issues-N.pb에 여러 이슈가 있을 때 모두 파싱된다")
    fun givenIssueFileWithMultipleIssues_whenExtractIssues_thenAllParsed() {
        // given
        val issue1 = buildIssueMessage("kotlin:S1", 1, "First issue", 1)
        val issue2 = buildIssueMessage("kotlin:S2", 2, "Second issue", 5)
        val zipBytes = buildZip("issues-1.pb" to buildDelimitedIssueFile(issue1, issue2))
        sonarAnalysisService.storeReport("my-project", zipBytes)

        // when
        val result = sonarAnalysisService.extractIssues("my-project")

        // then
        assertThat(result).hasSize(2)
        assertThat(result[0].ruleKey).isEqualTo("kotlin:S1")
        assertThat(result[1].ruleKey).isEqualTo("kotlin:S2")
    }

    @Test
    @DisplayName("severity ordinal이 올바르게 이름으로 변환된다")
    fun givenIssueSeverities_whenExtractIssues_thenSeverityNamesAreCorrect() {
        // given
        val issues = listOf(
            buildIssueMessage("r:1", 1, "m", 1),  // INFO
            buildIssueMessage("r:2", 2, "m", 2),  // MINOR
            buildIssueMessage("r:3", 3, "m", 3),  // MAJOR
            buildIssueMessage("r:4", 4, "m", 4),  // CRITICAL
            buildIssueMessage("r:5", 5, "m", 5),  // BLOCKER
        )
        val zipBytes = buildZip("issues-1.pb" to buildDelimitedIssueFile(*issues.toTypedArray()))
        sonarAnalysisService.storeReport("my-project", zipBytes)

        // when
        val result = sonarAnalysisService.extractIssues("my-project")

        // then
        assertThat(result.map { it.severity })
            .containsExactly("INFO", "MINOR", "MAJOR", "CRITICAL", "BLOCKER")
    }

    // ── protobuf test data builders ───────────────────────────────────────────────

    /**
     * Encodes a ScannerReport.Issue protobuf message.
     * Field numbers: 1=ruleKey, 2=line, 3=msg, 4=severity
     */
    private fun buildIssueMessage(ruleKey: String, line: Int, msg: String, severity: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val cos = CodedOutputStream.newInstance(baos)
        cos.writeString(1, ruleKey)
        cos.writeInt32(2, line)
        cos.writeString(3, msg)
        cos.writeEnum(4, severity)
        cos.flush()
        return baos.toByteArray()
    }

    /**
     * Encodes a ScannerReport.Component protobuf message.
     * Field number: 10=projectRelativePath
     */
    private fun buildComponentMessage(projectRelativePath: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val cos = CodedOutputStream.newInstance(baos)
        cos.writeString(10, projectRelativePath)
        cos.flush()
        return baos.toByteArray()
    }

    /**
     * Builds an issues-{N}.pb file body: each message prefixed with a varint size
     * (protobuf writeDelimitedTo format).
     */
    private fun buildDelimitedIssueFile(vararg issueMessages: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        for (msg in issueMessages) {
            writeVarint32(baos, msg.size)
            baos.write(msg)
        }
        return baos.toByteArray()
    }

    private fun writeVarint32(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    /** Creates an in-memory ZIP with the given named entries. */
    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
