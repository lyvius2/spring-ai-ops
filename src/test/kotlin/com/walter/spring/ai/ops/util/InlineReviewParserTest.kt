package com.walter.spring.ai.ops.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.DiffSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class InlineReviewParserTest {

    private lateinit var parser: InlineReviewParser

    @BeforeEach
    fun setUp() {
        val handler = CodeAnalysisResultHandler(ObjectMapper())
        parser = InlineReviewParser(handler)
    }

    @Test
    @DisplayName("빈 문자열 입력 → 빈 결과 반환")
    fun givenBlankInput_whenParse_thenReturnsEmptyResult() {
        // when
        val result = parser.parse("")

        // then
        assertThat(result.summary).isEmpty()
        assertThat(result.comments).isEmpty()
    }

    @Test
    @DisplayName("델리미터가 없으면 전체 텍스트를 summary로 반환하고 comments는 비어있음")
    fun givenNoDelimiter_whenParse_thenWholeTextIsSummary() {
        // given
        val raw = "This is a plain review with no JSON delimiter."

        // when
        val result = parser.parse(raw)

        // then
        assertThat(result.summary).isEqualTo("This is a plain review with no JSON delimiter.")
        assertThat(result.comments).isEmpty()
    }

    @Test
    @DisplayName("정상적인 markdown + JSON 배열 → summary와 comments 분리 파싱")
    fun givenWellFormedInput_whenParse_thenExtractsBoth() {
        // given
        val raw = """
            ## Summary
            Nice change overall.
            ---INLINE_COMMENTS_JSON_START---
            [
              {"file":"src/foo.kt","line":10,"side":"RIGHT","body":"Consider null check here"},
              {"file":"src/bar.kt","line":22,"side":"LEFT","body":"This line was removed for a reason?"}
            ]
            ---INLINE_COMMENTS_JSON_END---
        """.trimIndent()

        // when
        val result = parser.parse(raw)

        // then
        assertThat(result.summary).contains("Nice change overall.")
        assertThat(result.comments).hasSize(2)
        assertThat(result.comments[0].file).isEqualTo("src/foo.kt")
        assertThat(result.comments[0].line).isEqualTo(10)
        assertThat(result.comments[0].side).isEqualTo(DiffSide.RIGHT)
        assertThat(result.comments[0].body).isEqualTo("Consider null check here")
        assertThat(result.comments[1].side).isEqualTo(DiffSide.LEFT)
    }

    @Test
    @DisplayName("END 마커가 없어도 START 이후 텍스트에서 JSON을 파싱")
    fun givenMissingEndMarker_whenParse_thenStillParsesJson() {
        // given
        val raw = """
            summary line
            ---INLINE_COMMENTS_JSON_START---
            [{"file":"a.kt","line":1,"side":"RIGHT","body":"x"}]
        """.trimIndent()

        // when
        val result = parser.parse(raw)

        // then
        assertThat(result.summary).isEqualTo("summary line")
        assertThat(result.comments).hasSize(1)
        assertThat(result.comments[0].file).isEqualTo("a.kt")
    }

    @Test
    @DisplayName("빈 JSON 배열 → summary만 반환하고 comments는 비어있음")
    fun givenEmptyJsonArray_whenParse_thenSummaryOnly() {
        // given
        val raw = """
            No issues found.
            ---INLINE_COMMENTS_JSON_START---
            []
            ---INLINE_COMMENTS_JSON_END---
        """.trimIndent()

        // when
        val result = parser.parse(raw)

        // then
        assertThat(result.summary).isEqualTo("No issues found.")
        assertThat(result.comments).isEmpty()
    }

    @Test
    @DisplayName("side 값의 대소문자 차이는 DiffSide.fromRaw로 흡수됨")
    fun givenMixedCaseSide_whenParse_thenNormalizesToEnum() {
        // given
        val raw = """
            summary
            ---INLINE_COMMENTS_JSON_START---
            [
              {"file":"a.kt","line":1,"side":"right","body":"x"},
              {"file":"a.kt","line":2,"side":"Left","body":"y"}
            ]
            ---INLINE_COMMENTS_JSON_END---
        """.trimIndent()

        // when
        val result = parser.parse(raw)

        // then
        assertThat(result.comments[0].side).isEqualTo(DiffSide.RIGHT)
        assertThat(result.comments[1].side).isEqualTo(DiffSide.LEFT)
    }

    @Test
    @DisplayName("JSON body에 개행이 들어가 있어도 sanitizeControlChars가 흡수해서 파싱")
    fun givenControlCharsInBody_whenParse_thenSanitizesAndParses() {
        // given
        val raw = "sum\n---INLINE_COMMENTS_JSON_START---\n[{\"file\":\"a.kt\",\"line\":1,\"side\":\"RIGHT\",\"body\":\"line1\nline2\"}]\n---INLINE_COMMENTS_JSON_END---"

        // when
        val result = parser.parse(raw)

        // then
        assertThat(result.comments).hasSize(1)
        assertThat(result.comments[0].body).contains("line1")
        assertThat(result.comments[0].body).contains("line2")
    }

    @Test
    @DisplayName("일부 항목이 malformed면 recoverIssuesFromJson으로 파싱 가능한 항목만 복구")
    fun givenPartiallyMalformedJson_whenParse_thenRecoversValid() {
        // given — the second entry has a trailing comma inside making the array invalid
        val raw = """
            sum
            ---INLINE_COMMENTS_JSON_START---
            [
              {"file":"a.kt","line":1,"side":"RIGHT","body":"ok"},
              {"file":"b.kt","line":2,"side":"RIGHT","body":"bad",},
              {"file":"c.kt","line":3,"side":"RIGHT","body":"ok2"}
            ]
            ---INLINE_COMMENTS_JSON_END---
        """.trimIndent()

        // when
        val result = parser.parse(raw)

        // then — at minimum, the first valid entry is recovered
        assertThat(result.comments).isNotEmpty
        assertThat(result.comments.first().file).isEqualTo("a.kt")
    }
}