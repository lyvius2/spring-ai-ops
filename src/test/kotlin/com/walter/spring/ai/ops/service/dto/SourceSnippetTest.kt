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
}
