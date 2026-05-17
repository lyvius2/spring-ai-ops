package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.connector.SlackChannelConnector
import com.walter.spring.ai.ops.connector.dto.SlackMessageRequest
import com.walter.spring.ai.ops.connector.dto.SlackMessageResponse
import com.walter.spring.ai.ops.record.ChangedFile
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.util.MarkdownConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.LocalDateTime
import java.time.ZoneOffset

@Suppress("UNCHECKED_CAST")
private fun <T> anyObject(): T = Mockito.any() as T

@ExtendWith(MockitoExtension::class)
class SlackChannelServiceTest {

    @Mock
    private lateinit var slackChannelConnector: SlackChannelConnector

    private val markdownConverter = MarkdownConverter()

    private lateinit var service: SlackChannelService

    private val baseUrl = "http://aiops.example.com:7079"
    private val channelPath = "/services/T000/B000/xxxx"

    @BeforeEach
    fun setUp() {
        service = SlackChannelService(slackChannelConnector, markdownConverter, baseUrl)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun createRecord(
        application: String = "my-app",
        branch: String = "main",
        pushedAt: LocalDateTime = LocalDateTime.of(2026, 5, 17, 0, 6, 21),
        githubUrl: String? = "https://github.com/org/my-app",
        commitMessage: String? = "feat: add feature",
        reviewResult: String? = "## Summary\n\nOverall LGTM.",
        changedFiles: List<ChangedFile> = listOf(
            ChangedFile("src/Main.kt", "modified", 10, 2, null),
        ),
    ) = CodeReviewRecord.create(
        pushedAt,
        application,
        githubUrl,
        commitMessage,
        changedFiles,
        reviewResult,
        LocalDateTime.now(),
        emptyList(),
        branch,
    )

    /**
     * Stubs the connector and captures the first argument (SlackMessageRequest) via doAnswer.
     * Returns the captured message after invoking sendCodeReviewResult.
     */
    private fun sendAndCapture(
        record: CodeReviewRecord,
        overrideService: SlackChannelService = service,
    ): SlackMessageRequest {
        var captured: SlackMessageRequest? = null
        doAnswer { invocation ->
            captured = invocation.getArgument(0) as SlackMessageRequest
            SlackMessageResponse("ok")
        }.`when`(slackChannelConnector).sendMessage(anyObject(), anyString())
        overrideService.sendCodeReviewResult(record, channelPath)
        return captured!!
    }

    // ── sendCodeReviewResult — 커넥터 호출 ──────────────────────────────────

    @Nested
    @DisplayName("sendCodeReviewResult — 커넥터 호출")
    inner class SendCodeReviewResult {

        @Test
        @DisplayName("올바른 channelPath로 커넥터의 sendMessage가 호출된다")
        fun givenValidRecord_whenSendCodeReviewResult_thenCallsConnectorWithCorrectPath() {
            // given
            val record = createRecord()
            var capturedPath: String? = null
            doAnswer { invocation ->
                capturedPath = invocation.getArgument(1) as String
                SlackMessageResponse("ok")
            }.`when`(slackChannelConnector).sendMessage(anyObject(), anyString())

            // when
            service.sendCodeReviewResult(record, channelPath)

            // then
            assertThat(capturedPath).isEqualTo(channelPath)
        }

        @Test
        @DisplayName("fallback text에 application 이름과 branch가 포함된다")
        fun givenValidRecord_whenSendCodeReviewResult_thenFallbackTextContainsAppAndBranch() {
            // given
            val record = createRecord(application = "payment-service", branch = "release/1.0")

            // when
            val request = sendAndCapture(record)

            // then
            assertThat(request.text).contains("payment-service")
            assertThat(request.text).contains("release/1.0")
        }
    }

    // ── buildSlackMessage — 블록 구조 ────────────────────────────────────────

    @Nested
    @DisplayName("buildSlackMessage — 블록 구조")
    inner class MessageBlockStructure {

        @Test
        @DisplayName("reviewResult가 있으면 header + meta + divider + review 4개 블록이 생성된다")
        fun givenRecordWithReviewResult_whenBuildMessage_thenFourBlocks() {
            // given
            val record = createRecord(reviewResult = "LGTM")

            // when
            val blocks = sendAndCapture(record).blocks!!

            // then
            assertThat(blocks).hasSize(4)
            assertThat(blocks[0].type).isEqualTo("header")
            assertThat(blocks[1].type).isEqualTo("section") // meta
            assertThat(blocks[2].type).isEqualTo("divider")
            assertThat(blocks[3].type).isEqualTo("section") // review
        }

        @Test
        @DisplayName("reviewResult가 null이면 header + meta 2개 블록만 생성된다")
        fun givenRecordWithNullReviewResult_whenBuildMessage_thenTwoBlocks() {
            // given
            val record = createRecord(reviewResult = null)

            // when
            val blocks = sendAndCapture(record).blocks!!

            // then
            assertThat(blocks).hasSize(2)
        }

        @Test
        @DisplayName("reviewResult가 공백만 있으면 header + meta 2개 블록만 생성된다")
        fun givenRecordWithBlankReviewResult_whenBuildMessage_thenTwoBlocks() {
            // given
            val record = createRecord(reviewResult = "   ")

            // when
            val blocks = sendAndCapture(record).blocks!!

            // then
            assertThat(blocks).hasSize(2)
        }
    }

    // ── buildSlackMessage — 메타 섹션 내용 ──────────────────────────────────

    @Nested
    @DisplayName("buildSlackMessage — 메타 섹션 내용")
    inner class MetaSection {

        private fun metaText(record: CodeReviewRecord) =
            sendAndCapture(record).blocks!![1].text!!.text

        @Test
        @DisplayName("githubUrl이 있으면 메타에 angle-bracket 링크가 포함된다")
        fun givenRecordWithGithubUrl_whenBuildMessage_thenMetaContainsRepoLink() {
            // given
            val record = createRecord(githubUrl = "https://github.com/org/repo")

            // when / then
            assertThat(metaText(record)).contains("<https://github.com/org/repo|my-app>")
        }

        @Test
        @DisplayName("githubUrl이 없으면 메타에 application 이름만 표시된다")
        fun givenRecordWithoutGithubUrl_whenBuildMessage_thenMetaContainsAppNameOnly() {
            // given
            val record = createRecord(githubUrl = null)

            // when / then
            assertThat(metaText(record)).contains("*Repository:* my-app")
        }

        @Test
        @DisplayName("commitMessage가 있으면 메타에 포함된다")
        fun givenRecordWithCommitMessage_whenBuildMessage_thenMetaContainsCommit() {
            // given
            val record = createRecord(commitMessage = "fix: resolve NPE")

            // when / then
            assertThat(metaText(record)).contains("fix: resolve NPE")
        }

        @Test
        @DisplayName("commitMessage가 null이면 메타에 포함되지 않는다")
        fun givenRecordWithNullCommitMessage_whenBuildMessage_thenMetaExcludesCommit() {
            // given
            val record = createRecord(commitMessage = null)

            // when / then
            assertThat(metaText(record)).doesNotContain("*Commit:*")
        }

        @Test
        @DisplayName("changedFiles가 있으면 개수가 메타에 포함된다")
        fun givenRecordWithChangedFiles_whenBuildMessage_thenMetaContainsFileCount() {
            // given
            val files = listOf(
                ChangedFile("A.kt", "added", 1, 0, null),
                ChangedFile("B.kt", "modified", 2, 1, null),
            )
            val record = createRecord(changedFiles = files)

            // when / then
            assertThat(metaText(record)).contains("*Changed files:* 2")
        }

        @Test
        @DisplayName("changedFiles가 비어있으면 파일 개수가 메타에 포함되지 않는다")
        fun givenRecordWithEmptyChangedFiles_whenBuildMessage_thenMetaExcludesFileCount() {
            // given
            val record = createRecord(changedFiles = emptyList())

            // when / then
            assertThat(metaText(record)).doesNotContain("*Changed files:*")
        }
    }

    // ── buildDirectUrl — direct URL 생성 ────────────────────────────────────

    @Nested
    @DisplayName("buildDirectUrl — direct URL 생성")
    inner class BuildDirectUrl {

        private fun metaText(record: CodeReviewRecord, svc: SlackChannelService = service) =
            sendAndCapture(record, svc).blocks!![1].text!!.text

        @Test
        @DisplayName("baseUrl이 설정되면 메타에 Open in AIOps 링크가 포함된다")
        fun givenBaseUrlConfigured_whenBuildMessage_thenMetaContainsDirectLink() {
            // given
            val record = createRecord()

            // when / then
            val text = metaText(record)
            assertThat(text).contains("Open in AIOps")
            assertThat(text).contains("http://aiops.example.com:7079")
        }

        @Test
        @DisplayName("URL에 app 이름, codereview 탭, epoch millis가 포함된다")
        fun givenConfiguredBaseUrl_whenBuildMessage_thenDirectUrlContainsEpochMs() {
            // given
            val pushedAt = LocalDateTime.of(2026, 5, 17, 0, 6, 21)
            val expectedEpochMs = pushedAt.toInstant(ZoneOffset.UTC).toEpochMilli()
            val record = createRecord(application = "my-app", pushedAt = pushedAt)

            // when / then
            val text = metaText(record)
            assertThat(text).contains("my-app/codereview/$expectedEpochMs")
        }

        @Test
        @DisplayName("baseUrl이 비어있으면 메타에 Open in AIOps 링크가 포함되지 않는다")
        fun givenBlankBaseUrl_whenBuildMessage_thenNoDirectLink() {
            // given
            val serviceWithoutBaseUrl = SlackChannelService(slackChannelConnector, markdownConverter, "")
            val record = createRecord()

            // when / then
            val text = metaText(record, serviceWithoutBaseUrl)
            assertThat(text).doesNotContain("Open in AIOps")
        }

        @Test
        @DisplayName("baseUrl 끝의 슬래시는 제거되어 URL에 이중 슬래시가 없다")
        fun givenBaseUrlWithTrailingSlash_whenBuildMessage_thenNoDoubleSlash() {
            // given
            val serviceWithSlash = SlackChannelService(
                slackChannelConnector, markdownConverter, "http://aiops.example.com/"
            )
            val record = createRecord()

            // when / then
            val text = metaText(record, serviceWithSlash)
            assertThat(text).doesNotContain("//codereview")
        }
    }
}
