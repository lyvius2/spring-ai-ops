package com.walter.spring.ai.ops.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MarkdownConverterTest {

    private lateinit var converter: MarkdownConverter

    @BeforeEach
    fun setUp() {
        converter = MarkdownConverter()
    }

    // ── toSlackMrkdwn ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toSlackMrkdwn — 헤더 변환")
    inner class Headers {

        @Test
        @DisplayName("H1 헤더는 볼드 텍스트로 변환된다")
        fun givenH1Header_whenToSlackMrkdwn_thenReturnsBold() {
            // given
            val markdown = "# Title"

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("*Title*")
        }

        @Test
        @DisplayName("H2~H6 헤더도 동일하게 볼드 텍스트로 변환된다")
        fun givenH2ToH6Headers_whenToSlackMrkdwn_thenReturnsBold() {
            // given / when / then
            assertThat(converter.toSlackMrkdwn("## Section")).isEqualTo("*Section*")
            assertThat(converter.toSlackMrkdwn("### Sub")).isEqualTo("*Sub*")
            assertThat(converter.toSlackMrkdwn("###### H6")).isEqualTo("*H6*")
        }
    }

    @Nested
    @DisplayName("toSlackMrkdwn — 강조 변환")
    inner class Emphasis {

        @Test
        @DisplayName("별표 볼드(**text**)는 mrkdwn 볼드(*text*)로 변환된다")
        fun givenAsteriskBold_whenToSlackMrkdwn_thenReturnsMrkdwnBold() {
            // given
            val markdown = "This is **bold** text."

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("This is *bold* text.")
        }

        @Test
        @DisplayName("언더스코어 볼드(__text__)는 mrkdwn 볼드(*text*)로 변환된다")
        fun givenUnderscoreBold_whenToSlackMrkdwn_thenReturnsMrkdwnBold() {
            // given
            val markdown = "This is __bold__ text."

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("This is *bold* text.")
        }

        @Test
        @DisplayName("별표 이탤릭(*text*)은 mrkdwn 이탤릭(_text_)으로 변환된다")
        fun givenAsteriskItalic_whenToSlackMrkdwn_thenReturnsMrkdwnItalic() {
            // given
            val markdown = "This is *italic* text."

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("This is _italic_ text.")
        }

        @Test
        @DisplayName("언더스코어 이탤릭(_text_)은 mrkdwn 이탤릭(_text_)으로 변환된다")
        fun givenUnderscoreItalic_whenToSlackMrkdwn_thenReturnsMrkdwnItalic() {
            // given
            val markdown = "This is _italic_ text."

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("This is _italic_ text.")
        }

        @Test
        @DisplayName("취소선(~~text~~)은 mrkdwn 취소선(~text~)으로 변환된다")
        fun givenStrikethrough_whenToSlackMrkdwn_thenReturnsMrkdwnStrikethrough() {
            // given
            val markdown = "This is ~~strikethrough~~ text."

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("This is ~strikethrough~ text.")
        }
    }

    @Nested
    @DisplayName("toSlackMrkdwn — 코드 변환")
    inner class Code {

        @Test
        @DisplayName("인라인 코드(`code`)는 보호되어 다른 변환에 영향받지 않는다")
        fun givenInlineCode_whenToSlackMrkdwn_thenPreservesCode() {
            // given
            val markdown = "Use `**bold**` here."

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("Use `**bold**` here.")
        }

        @Test
        @DisplayName("펜스드 코드 블록은 Slack 코드 블록으로 변환되고 언어 힌트는 제거된다")
        fun givenFencedCodeBlock_whenToSlackMrkdwn_thenConvertsToSlackCodeBlock() {
            // given
            val markdown = "```kotlin\nval x = 1\n```"

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("```\nval x = 1\n```")
        }

        @Test
        @DisplayName("펜스드 코드 블록 내부의 볼드 마크다운은 변환되지 않는다")
        fun givenBoldInsideCodeBlock_whenToSlackMrkdwn_thenNotConverted() {
            // given
            val markdown = "```\n**not bold**\n```"

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).contains("**not bold**")
        }
    }

    @Nested
    @DisplayName("toSlackMrkdwn — 링크 및 이미지 변환")
    inner class Links {

        @Test
        @DisplayName("[label](url) 형식 링크는 Slack angle-bracket 형식으로 변환된다")
        fun givenMarkdownLink_whenToSlackMrkdwn_thenConvertsToSlackLink() {
            // given
            val markdown = "See [GitHub](https://github.com) for details."

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("See <https://github.com|GitHub> for details.")
        }

        @Test
        @DisplayName("이미지 링크(![alt](url))도 Slack angle-bracket 형식으로 변환된다")
        fun givenMarkdownImage_whenToSlackMrkdwn_thenConvertsToSlackLink() {
            // given
            val markdown = "![logo](https://example.com/logo.png)"

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("<https://example.com/logo.png|logo>")
        }
    }

    @Nested
    @DisplayName("toSlackMrkdwn — 목록 및 구분선 변환")
    inner class ListsAndRules {

        @Test
        @DisplayName("하이픈 목록(- item)은 bullet(•) 형식으로 변환된다")
        fun givenHyphenList_whenToSlackMrkdwn_thenReturnsBulletList() {
            // given
            val markdown = "- item one\n- item two"

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("• item one\n• item two")
        }

        @Test
        @DisplayName("별표/플러스 목록(*, + item)도 bullet(•) 형식으로 변환된다")
        fun givenAsteriskAndPlusList_whenToSlackMrkdwn_thenReturnsBulletList() {
            // given / when / then
            assertThat(converter.toSlackMrkdwn("* item")).isEqualTo("• item")
            assertThat(converter.toSlackMrkdwn("+ item")).isEqualTo("• item")
        }

        @Test
        @DisplayName("수평선(--- / *** / ___)은 제거된다")
        fun givenHorizontalRule_whenToSlackMrkdwn_thenRemoved() {
            // given
            val markdown = "above\n---\nbelow"

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).doesNotContain("---")
            assertThat(result).contains("above")
            assertThat(result).contains("below")
        }
    }

    @Nested
    @DisplayName("toSlackMrkdwn — 복합 케이스")
    inner class Complex {

        @Test
        @DisplayName("볼드와 이탤릭이 혼합된 경우 각각 올바르게 변환된다")
        fun givenBoldAndItalicMixed_whenToSlackMrkdwn_thenBothConverted() {
            // given
            val markdown = "**bold** and *italic*"

            // when
            val result = converter.toSlackMrkdwn(markdown)

            // then
            assertThat(result).isEqualTo("*bold* and _italic_")
        }

        @Test
        @DisplayName("빈 문자열을 입력하면 빈 문자열이 반환된다")
        fun givenEmptyString_whenToSlackMrkdwn_thenReturnsEmpty() {
            // given / when / then
            assertThat(converter.toSlackMrkdwn("")).isEqualTo("")
        }

        @Test
        @DisplayName("공백만 있는 문자열을 입력하면 trim되어 빈 문자열이 반환된다")
        fun givenWhitespaceOnly_whenToSlackMrkdwn_thenReturnsEmpty() {
            // given / when / then
            assertThat(converter.toSlackMrkdwn("   \n  ")).isEqualTo("")
        }
    }

    // ── truncate ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("truncate — 길이 제한")
    inner class Truncate {

        @Test
        @DisplayName("텍스트 길이가 maxLength 이하이면 그대로 반환된다")
        fun givenTextWithinLimit_whenTruncate_thenReturnsOriginal() {
            // given
            val text = "short text"

            // when
            val result = converter.truncate(text)

            // then
            assertThat(result).isEqualTo(text)
        }

        @Test
        @DisplayName("텍스트 길이가 maxLength를 초과하면 잘리고 기본 suffix가 추가된다")
        fun givenTextExceedingLimit_whenTruncate_thenAddsDefaultSuffix() {
            // given
            val text = "A".repeat(3_100)

            // when
            val result = converter.truncate(text)

            // then
            assertThat(result.length).isLessThanOrEqualTo(3_000)
            assertThat(result).endsWith("\n\n_[message truncated]_")
        }

        @Test
        @DisplayName("linkUrl이 지정되면 잘린 텍스트에 링크 suffix가 추가된다")
        fun givenTextExceedingLimitWithLinkUrl_whenTruncate_thenAddsLinkSuffix() {
            // given
            val text = "B".repeat(3_100)
            val linkUrl = "https://example.com/view"

            // when
            val result = converter.truncate(text, linkUrl)

            // then
            assertThat(result.length).isLessThanOrEqualTo(3_000)
            assertThat(result).contains("https://example.com/view")
            assertThat(result).contains("this link")
        }

        @Test
        @DisplayName("maxLength를 직접 지정하면 해당 길이로 제한된다")
        fun givenCustomMaxLength_whenTruncate_thenRespectsCustomLimit() {
            // given
            val text = "Hello, World! This is a longer text."
            val maxLength = 20

            // when
            val result = converter.truncate(text, maxLength = maxLength)

            // then
            assertThat(result.length).isLessThanOrEqualTo(maxLength)
        }

        @Test
        @DisplayName("텍스트 길이가 정확히 maxLength이면 잘리지 않는다")
        fun givenTextExactlyAtLimit_whenTruncate_thenReturnsOriginal() {
            // given
            val text = "C".repeat(3_000)

            // when
            val result = converter.truncate(text)

            // then
            assertThat(result).isEqualTo(text)
        }
    }

    // ── buildLinkSuffix ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildLinkSuffix")
    inner class BuildLinkSuffix {

        @Test
        @DisplayName("URL이 포함된 mrkdwn 포맷의 suffix 문자열이 반환된다")
        fun givenUrl_whenBuildLinkSuffix_thenReturnsFormattedSuffix() {
            // given
            val url = "https://my-aiops.example.com/#my-app/codereview/1747548381234"

            // when
            val result = converter.buildLinkSuffix(url)

            // then
            assertThat(result).startsWith("\n\n")
            assertThat(result).contains("<$url|this link>")
        }
    }
}

