package com.walter.spring.ai.ops.service.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class IncidentSourceContextTest {

    @Test
    @DisplayName("IncidentSourceContext는 파싱된 프레임, 스니펫, 미해결 프레임을 보관함")
    fun givenFramesAndSnippets_whenCreatingDto_thenStoresIncidentSourceContext() {
        // given
        val resolvedFrame = StackTraceFrame("com.example.FooService", "doWork", "FooService.kt", 42)
        val unresolvedFrame = StackTraceFrame("com.example.BarService", "doWork", "BarService.kt", 19)
        val snippet = SourceSnippet(
            filePath = "src/main/kotlin/com/example/FooService.kt",
            startLine = 2,
            endLine = 82,
            focusLine = 42,
            content = ">> 42 | doWork()",
        )

        // when
        val context = IncidentSourceContext(
            frames = listOf(resolvedFrame, unresolvedFrame),
            snippets = listOf(snippet),
            unresolvedFrames = listOf(unresolvedFrame),
        )

        // then
        assertThat(context.frames).containsExactly(resolvedFrame, unresolvedFrame)
        assertThat(context.snippets).containsExactly(snippet)
        assertThat(context.unresolvedFrames).containsExactly(unresolvedFrame)
    }
}
