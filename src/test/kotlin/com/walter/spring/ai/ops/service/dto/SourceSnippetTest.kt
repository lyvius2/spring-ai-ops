package com.walter.spring.ai.ops.service.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SourceSnippetTest {

    @Test
    @DisplayName("SourceSnippet은 파일 경로와 라인 범위, 포커스 라인, 내용을 보관함")
    fun givenSourceSnippetValues_whenCreatingDto_thenStoresAllValues() {
        // given
        val filePath = "src/main/kotlin/com/example/FooService.kt"
        val content = ">> 42 | return response.body"

        // when
        val snippet = SourceSnippet(
            filePath = filePath,
            startLine = 2,
            endLine = 82,
            focusLine = 42,
            content = content,
        )

        // then
        assertThat(snippet.filePath).isEqualTo(filePath)
        assertThat(snippet.startLine).isEqualTo(2)
        assertThat(snippet.endLine).isEqualTo(82)
        assertThat(snippet.focusLine).isEqualTo(42)
        assertThat(snippet.content).isEqualTo(content)
    }

    @Test
    @DisplayName("SourceSnippet은 source snippet prompt를 생성함")
    fun givenSourceSnippet_whenCreateSourceSnippetPrompt_thenReturnsSnippetPrompt() {
        // given
        val snippet = SourceSnippet(
            filePath = "src/main/kotlin/com/example/FooService.kt",
            startLine = 2,
            endLine = 6,
            focusLine = 5,
            content = ">>    5 | error(\"failed\")",
        )

        // when
        val prompt = snippet.createSourceSnippetPrompt()

        // then
        assertThat(prompt).contains("File: src/main/kotlin/com/example/FooService.kt")
        assertThat(prompt).contains("Lines: 2-6")
        assertThat(prompt).contains("Focus line: 5")
        assertThat(prompt).contains(">>    5 | error(\"failed\")")
    }

    @Test
    @DisplayName("focusLine이 없으면 SourceSnippet prompt에 Focus line을 포함하지 않음")
    fun givenSourceSnippetWithoutFocusLine_whenCreateSourceSnippetPrompt_thenOmitsFocusLine() {
        // given
        val snippet = SourceSnippet(
            filePath = "src/main/kotlin/com/example/FooService.kt",
            startLine = 1,
            endLine = 80,
            focusLine = null,
            content = "      1 | package com.example",
        )

        // when
        val prompt = snippet.createSourceSnippetPrompt()

        // then
        assertThat(prompt).contains("File: src/main/kotlin/com/example/FooService.kt")
        assertThat(prompt).contains("Lines: 1-80")
        assertThat(prompt).doesNotContain("Focus line:")
    }
}
