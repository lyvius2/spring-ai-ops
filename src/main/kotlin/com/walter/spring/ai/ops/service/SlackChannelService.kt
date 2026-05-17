package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.connector.SlackChannelConnector
import com.walter.spring.ai.ops.connector.dto.SlackBlock
import com.walter.spring.ai.ops.connector.dto.SlackMessageRequest
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.util.MarkdownConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class SlackChannelService(
    private val slackChannelConnector: SlackChannelConnector,
    private val markdownConverter: MarkdownConverter,
    @Value("\${app.base-url:}")
    private val appBaseUrl: String,
) {
    fun sendCodeReviewResult(codeReviewRecord: CodeReviewRecord, slackChannelPath: String) {
        val message = buildSlackMessage(codeReviewRecord)
        slackChannelConnector.sendMessage(message, slackChannelPath)
    }

    /**
     * Builds a Slack Block Kit message from a [CodeReviewRecord].
     *
     * Layout:
     * ┌─────────────────────────────────────────────┐
     * │  header  — Code Review: {app} [{branch}]    │
     * │  section — Repository / Commit metadata     │
     * │  divider                                    │
     * │  section — LLM review result (mrkdwn)       │
     * └─────────────────────────────────────────────┘
     *
     * The top-level `text` field serves as the notification fallback.
     */
    private fun buildSlackMessage(record: CodeReviewRecord): SlackMessageRequest {
        val fallbackText = "Code Review completed — ${record.application()} [${record.branch()}]"

        val headerText = "Code Review: ${record.application()} [${record.branch()}]"
        val headerBlock = SlackBlock.header(headerText)
        val metaLines = buildList {
            add("*Repository:* ${buildRepoLink(record)}")
            add("*Branch:* `${record.branch()}`")
            if (!record.commitMessage().isNullOrBlank()) {
                add("*Commit:* ${record.commitMessage()}")
            }
            if (!record.changedFiles().isNullOrEmpty()) {
                add("*Changed files:* ${record.changedFiles().size}")
            }
            val directUrl = buildDirectUrl(record)
            if (directUrl != null) {
                add("*View:* <$directUrl|Open in AIOps>")
            }
        }
        val metaBlock = SlackBlock.section(metaLines.joinToString("\n"))
        val dividerBlock = SlackBlock(type = "divider")
        val reviewMrkdwn = markdownConverter.toSlackMrkdwn(record.reviewResult() ?: "")
        val truncated = markdownConverter.truncate(reviewMrkdwn)
        val blocks = if (truncated.isBlank()) {
            listOf(headerBlock, metaBlock)
        } else {
            listOf(headerBlock, metaBlock, dividerBlock, SlackBlock.section(truncated))
        }
        return SlackMessageRequest(
            text = fallbackText,
            blocks = blocks,
        )
    }

    private fun buildRepoLink(record: CodeReviewRecord): String {
        val url = record.githubUrl()
        return if (!url.isNullOrBlank()) {
            "<$url|${record.application()}>"
        } else {
            record.application()
        }
    }

    private fun buildDirectUrl(record: CodeReviewRecord): String? {
        val base = appBaseUrl.trimEnd('/')
        if (base.isBlank()) return null
        val pushedAt = record.pushedAt() ?: return null
        val epochMs = pushedAt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val encodedApp = java.net.URLEncoder.encode(record.application(), "UTF-8")
        return "$base/#$encodedApp/codereview/$epochMs"
    }
}