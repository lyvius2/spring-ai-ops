package com.walter.spring.ai.ops.util

import org.springframework.stereotype.Component

/**
 * Converts Markdown-formatted text to Slack mrkdwn format and handles message length constraints.
 *
 * Conversion rules applied in order:
 *  1. Fenced code blocks  (triple-backtick + lang)  — language hint stripped, content protected
 *  2. Inline code         (single backtick)          — protected from other conversions
 *  3. Headers             (# through ######)         — converted to Slack bold line
 *  4. Italic              (*text* or _text_)         — converted to _text_
 *  5. Bold                (**text** or __text__)     — converted to *text*
 *  6. Strikethrough       (~~text~~)                 — converted to ~text~
 *  7. Links / images      ([label](url))             — converted to angle-bracket format
 *  8. Unordered lists     (-, *, + prefix)           — converted to bullet character
 *  9. Horizontal rules    (---, ***, ___)            — removed
 *
 * Processing order is intentional: italic must precede bold to avoid misinterpreting
 * the single asterisks that remain after bold conversion.
 */
@Component
class MarkdownConverter {

    companion object {
        const val SLACK_MAX_LENGTH = 3_000
        private const val TRUNCATION_SUFFIX = "\n\n_[message truncated]_"
        private const val LINK_SUFFIX = "\n\n_...truncated. View full message at_<{url}|this link>"

        private val FENCED_CODE_BLOCK = Regex("```(\\w+)?\\n([\\s\\S]*?)```")
        private val INLINE_CODE = Regex("`([^`\\n]+?)`")
        private val HEADER = Regex("^#{1,6}\\s+(.+)$", RegexOption.MULTILINE)
        private val ITALIC_ASTERISK = Regex("(?<!\\*)\\*(?!\\*)([^*\\n]+?)(?<!\\*)\\*(?!\\*)")
        private val ITALIC_UNDERSCORE = Regex("(?<!_)_(?!_)([^_\\n]+?)(?<!_)_(?!_)")
        private val BOLD_ASTERISK = Regex("\\*\\*([^*\\n]+?)\\*\\*")
        private val BOLD_UNDERSCORE = Regex("__([^_\\n]+?)__")
        private val STRIKETHROUGH = Regex("~~([^~\\n]+?)~~")
        private val LINK = Regex("!?\\[([^\\[\\]]+?)]\\((https?://[^)]+?)\\)")
        private val UNORDERED_LIST = Regex("^[ \\t]*[-*+]\\s+(.+?)$", RegexOption.MULTILINE)
        private val HORIZONTAL_RULE = Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE)
    }

    /**
     * Converts [markdown] to Slack mrkdwn.
     *
     * @param markdown Markdown-formatted input string
     * @return mrkdwn-formatted string suitable for use in a Slack message
     */
    fun toSlackMrkdwn(markdown: String): String {
        val protected = mutableListOf<String>()

        fun protect(value: String): String {
            val placeholder = "\u0000P${protected.size}\u0000"
            protected.add(value)
            return placeholder
        }

        var text = markdown
        text = FENCED_CODE_BLOCK.replace(text) { m ->
            val code = m.groupValues[2].trimEnd('\n')
            protect("```\n$code\n```")
        }
        text = INLINE_CODE.replace(text) { m ->
            protect("`${m.groupValues[1]}`")
        }
        text = HEADER.replace(text) { "*${it.groupValues[1].trim()}*" }
        text = ITALIC_ASTERISK.replace(text) { "_${it.groupValues[1]}_" }
        text = ITALIC_UNDERSCORE.replace(text) { "_${it.groupValues[1]}_" }
        text = BOLD_ASTERISK.replace(text) { "*${it.groupValues[1]}*" }
        text = BOLD_UNDERSCORE.replace(text) { "*${it.groupValues[1]}*" }
        text = STRIKETHROUGH.replace(text) { "~${it.groupValues[1]}~" }
        text = LINK.replace(text) { "<${it.groupValues[2]}|${it.groupValues[1]}>" }
        text = UNORDERED_LIST.replace(text) { "• ${it.groupValues[1]}" }
        text = HORIZONTAL_RULE.replace(text, "")
        protected.forEachIndexed { index, block ->
            text = text.replace("\u0000P$index\u0000", block)
        }
        return text.trim()
    }

    /**
     * Truncates [text] to at most [maxLength] characters.
     *
     * If the text exceeds [maxLength], it is cut at a safe boundary and a
     * mrkdwn-formatted truncation notice is appended so recipients know the
     * message was shortened.
     *
     * @param text      the mrkdwn string to truncate
     * @param maxLength maximum allowed character count (default: [SLACK_MAX_LENGTH])
     * @return the original string if within limit, otherwise the truncated string with suffix
     */
    fun truncate(text: String, linkUrl: String? = null, maxLength: Int = SLACK_MAX_LENGTH): String {
        if (text.length <= maxLength) {
            return text
        }
        if (linkUrl.isNullOrBlank()) {
            val cutPoint = maxLength - TRUNCATION_SUFFIX.length
            return text.substring(0, cutPoint).trimEnd() + TRUNCATION_SUFFIX
        }
        val cutPoint = maxLength - LINK_SUFFIX.length - linkUrl.length
        return text.substring(0, cutPoint).trimEnd() + LINK_SUFFIX.replace("{url}", linkUrl)
    }
}
