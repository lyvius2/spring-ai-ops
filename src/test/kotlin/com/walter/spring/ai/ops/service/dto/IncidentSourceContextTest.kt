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

    @Test
    @DisplayName("snippet이 있으면 Related source snippets prompt section을 생성함")
    fun givenContextWithSnippet_whenCreateSourceSectionPrompt_thenReturnsPromptSection() {
        // given
        val context = IncidentSourceContext(
            frames = emptyList(),
            snippets = listOf(
                SourceSnippet(
                    filePath = "src/main/kotlin/com/example/FooService.kt",
                    startLine = 2,
                    endLine = 6,
                    focusLine = 5,
                    content = ">>    5 | error(\"failed\")",
                )
            ),
            unresolvedFrames = emptyList(),
        )

        // when
        val prompt = context.createSourceSectionPrompt()

        // then
        assertThat(prompt).contains("## Related source snippets")
        assertThat(prompt).contains("File: src/main/kotlin/com/example/FooService.kt")
        assertThat(prompt).contains("Lines: 2-6")
        assertThat(prompt).contains("Focus line: 5")
        assertThat(prompt).contains(">>    5 | error(\"failed\")")
    }

    @Test
    @DisplayName("snippet이 없으면 source prompt section은 빈 문자열임")
    fun givenContextWithoutSnippet_whenCreateSourceSectionPrompt_thenReturnsEmptyString() {
        // given
        val context = IncidentSourceContext(
            frames = emptyList(),
            snippets = emptyList(),
            unresolvedFrames = emptyList(),
        )

        // when
        val prompt = context.createSourceSectionPrompt()

        // then
        assertThat(prompt).isEmpty()
    }
}
