package com.walter.spring.ai.ops.util

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.record.CodeRiskIssue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CodeAnalysisResultHandlerTest {

    private lateinit var handler: CodeAnalysisResultHandler

    @BeforeEach
    fun setUp() {
        val lenientMapper = ObjectMapper().apply {
            configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
            configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
        }
        handler = CodeAnalysisResultHandler(lenientMapper)
    }

    // ── parseJsonArray ────────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 JSON 배열로 파싱 성공")
    fun givenValidJsonArray_whenParseJsonArray_thenReturnsParsedList() {
        // given
        val json = """
            [
              {"file":"Service.kt","line":"10","severity":"HIGH","description":"NPE risk","recommendation":"Add null check","codeSnippet":null},
              {"file":"Repo.kt","line":"20","severity":"LOW","description":"Unused import","recommendation":"Remove it","codeSnippet":null}
            ]
        """.trimIndent()

        // when
        val result = handler.parseJsonArray(json, CodeRiskIssue::class.java)

        // then
        assertThat(result).hasSize(2)
        assertThat(result[0].file).isEqualTo("Service.kt")
        assertThat(result[0].severity).isEqualTo("HIGH")
        assertThat(result[1].file).isEqualTo("Repo.kt")
        assertThat(result[1].severity).isEqualTo("LOW")
    }

    @Test
    @DisplayName("빈 JSON 배열 파싱 시 빈 리스트 반환")
    fun givenEmptyJsonArray_whenParseJsonArray_thenReturnsEmptyList() {
        // given
        val json = "[]"

        // when
        val result = handler.parseJsonArray(json, CodeRiskIssue::class.java)

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("잘못된 JSON 입력 시 예외 발생")
    fun givenInvalidJson_whenParseJsonArray_thenThrowsException() {
        // given
        val json = "not-valid-json"

        // when / then
        assertThatThrownBy { handler.parseJsonArray(json, CodeRiskIssue::class.java) }
            .isInstanceOf(Exception::class.java)
    }

    // ── sanitizeControlChars ──────────────────────────────────────────────────

    @Test
    @DisplayName("문자열 내부 개행 문자를 \\n 이스케이프로 변환")
    fun givenJsonWithNewlineInString_whenSanitizeControlChars_thenEscapesNewline() {
        // given
        val json = """{"description":"line1
line2"}"""

        // when
        val result = handler.sanitizeControlChars(json)

        // then
        assertThat(result).isEqualTo("""{"description":"line1\nline2"}""")
    }

    @Test
    @DisplayName("문자열 내부 탭 문자를 \\t 이스케이프로 변환")
    fun givenJsonWithTabInString_whenSanitizeControlChars_thenEscapesTab() {
        // given
        val json = "{\"code\":\"a\tb\"}"

        // when
        val result = handler.sanitizeControlChars(json)

        // then
        assertThat(result).isEqualTo("{\"code\":\"a\\tb\"}")
    }

    @Test
    @DisplayName("문자열 내부 캐리지 리턴을 \\r 이스케이프로 변환")
    fun givenJsonWithCarriageReturnInString_whenSanitizeControlChars_thenEscapesCr() {
        // given
        val json = "{\"msg\":\"a\rb\"}"

        // when
        val result = handler.sanitizeControlChars(json)

        // then
        assertThat(result).isEqualTo("{\"msg\":\"a\\rb\"}")
    }

    @Test
    @DisplayName("문자열 외부의 개행 문자는 변환하지 않음")
    fun givenNewlineOutsideString_whenSanitizeControlChars_thenKeepsAsIs() {
        // given
        val json = "{\n\"key\":\"value\"\n}"

        // when
        val result = handler.sanitizeControlChars(json)

        // then
        assertThat(result).isEqualTo("{\n\"key\":\"value\"\n}")
    }

    @Test
    @DisplayName("백슬래시 이스케이프 시퀀스는 변환하지 않음")
    fun givenEscapeSequenceInString_whenSanitizeControlChars_thenPreservesEscape() {
        // given
        val json = """{"path":"C:\\Users\\file.txt"}"""

        // when
        val result = handler.sanitizeControlChars(json)

        // then
        assertThat(result).isEqualTo("""{"path":"C:\\Users\\file.txt"}""")
    }

    @Test
    @DisplayName("0x20 미만의 제어 문자를 \\uXXXX 로 이스케이프")
    fun givenLowControlCharInString_whenSanitizeControlChars_thenEscapesAsUnicode() {
        // given — ASCII 0x01 (SOH) inside a JSON string
        val json = "{\"data\":\"a\u0001b\"}"

        // when
        val result = handler.sanitizeControlChars(json)

        // then
        assertThat(result).isEqualTo("{\"data\":\"a\\u0001b\"}")
    }

    @Test
    @DisplayName("제어 문자가 없는 일반 JSON은 그대로 반환")
    fun givenCleanJson_whenSanitizeControlChars_thenReturnsUnchanged() {
        // given
        val json = """{"file":"Service.kt","severity":"HIGH"}"""

        // when
        val result = handler.sanitizeControlChars(json)

        // then
        assertThat(result).isEqualTo(json)
    }

    // ── recoverIssuesFromJson ─────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 JSON 배열에서 모든 객체 복구")
    fun givenValidJsonArray_whenRecoverIssuesFromJson_thenReturnsAllItems() {
        // given
        val json = """
            [
              {"file":"A.kt","line":"1","severity":"HIGH","description":"d1","recommendation":"r1","codeSnippet":null},
              {"file":"B.kt","line":"2","severity":"LOW","description":"d2","recommendation":"r2","codeSnippet":null}
            ]
        """.trimIndent()

        // when
        val result = handler.recoverIssuesFromJson(json, CodeRiskIssue::class.java)

        // then
        assertThat(result).hasSize(2)
        assertThat(result[0].file).isEqualTo("A.kt")
        assertThat(result[1].file).isEqualTo("B.kt")
    }

    @Test
    @DisplayName("일부 객체가 잘못된 JSON 배열에서 유효한 객체만 복구")
    fun givenPartiallyInvalidJsonArray_whenRecoverIssuesFromJson_thenReturnsValidItems() {
        // given — second entry has an unquoted key (invalid), but lenient parser tolerates it
        // Use a truncated array that cuts off at the second object boundary
        val json = """[{"file":"A.kt","line":"1","severity":"HIGH","description":"d1","recommendation":"r1","codeSnippet":null}"""

        // when
        val result = handler.recoverIssuesFromJson(json, CodeRiskIssue::class.java)

        // then — at least the first valid object should be recovered
        assertThat(result).isNotEmpty
        assertThat(result[0].file).isEqualTo("A.kt")
    }

    @Test
    @DisplayName("완전히 잘못된 JSON 입력 시 빈 리스트 반환")
    fun givenCompletelyInvalidJson_whenRecoverIssuesFromJson_thenReturnsEmptyList() {
        // given
        val json = "THIS IS NOT JSON AT ALL %%##"

        // when
        val result = handler.recoverIssuesFromJson(json, CodeRiskIssue::class.java)

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("빈 배열 입력 시 빈 리스트 반환")
    fun givenEmptyJsonArray_whenRecoverIssuesFromJson_thenReturnsEmptyList() {
        // given
        val json = "[]"

        // when
        val result = handler.recoverIssuesFromJson(json, CodeRiskIssue::class.java)

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("배열이 아닌 단일 객체 JSON 입력 시 해당 객체 하나를 반환")
    fun givenSingleObjectJson_whenRecoverIssuesFromJson_thenReturnsSingleItem() {
        // given — recoverIssuesFromJson tolerates a bare object (no array wrapper)
        val json = """{"file":"A.kt","line":"1","severity":"HIGH","description":"d","recommendation":"r","codeSnippet":null}"""

        // when
        val result = handler.recoverIssuesFromJson(json, CodeRiskIssue::class.java)

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].file).isEqualTo("A.kt")
    }
}

