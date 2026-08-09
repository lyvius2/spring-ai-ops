package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.service.dto.LlmInlineComment
import com.walter.spring.ai.ops.service.dto.LlmInlineReviewResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class InlineReviewParser(
    private val codeAnalysisResultHandler: CodeAnalysisResultHandler,
) {
    private val log = LoggerFactory.getLogger(InlineReviewParser::class.java)

    fun parse(raw: String): LlmInlineReviewResult {
        if (raw.isBlank()) {
            return LlmInlineReviewResult()
        }
        val startIdx = raw.indexOf(START_MARKER)
        if (startIdx == -1) {
            return LlmInlineReviewResult(summary = raw.trim())
        }

        val summary = raw.substring(0, startIdx).trim()
        val afterStart = raw.substring(startIdx + START_MARKER.length)
        val endIdx = afterStart.indexOf(END_MARKER)
        val jsonText = if (endIdx == -1) afterStart else afterStart.substring(0, endIdx)
        val sanitized = codeAnalysisResultHandler.sanitizeControlChars(jsonText.trim())

        val comments = runCatching {
            codeAnalysisResultHandler.parseJsonArray(sanitized, LlmInlineComment::class.java)
        }.getOrElse {
            log.warn("Failed to parse inline comments JSON — attempting recovery: {}", it.message)
            codeAnalysisResultHandler.recoverIssuesFromJson(sanitized, LlmInlineComment::class.java)
        }
        return LlmInlineReviewResult(summary = summary, comments = comments)
    }

    companion object {
        private const val START_MARKER = "---INLINE_COMMENTS_JSON_START---"
        private const val END_MARKER   = "---INLINE_COMMENTS_JSON_END---"
    }
}