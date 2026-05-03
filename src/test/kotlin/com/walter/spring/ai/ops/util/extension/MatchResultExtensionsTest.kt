package com.walter.spring.ai.ops.util.extension

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MatchResultExtensionsTest {

    private val frameRegex = Regex("""\s*at\s+([\w.$]+)\.([\w$<>]+)\(([^():]+)(?::(\d+))?\)""")

    @Test
    @DisplayName("MatchResult를 StackTraceFrame으로 변환함")
    fun givenJvmStackTraceMatchResult_whenToStackTraceFrame_thenReturnsStackTraceFrame() {
        // given
        val matchResult = frameRegex.find("    at com.example.FooService.doWork(FooService.kt:42)")
            ?: error("Expected stack trace frame to match")

        // when
        val frame = matchResult.toStackTraceFrame()

        // then
        assertThat(frame.className).isEqualTo("com.example.FooService")
        assertThat(frame.methodName).isEqualTo("doWork")
        assertThat(frame.fileName).isEqualTo("FooService.kt")
        assertThat(frame.lineNumber).isEqualTo(42)
    }

    @Test
    @DisplayName("Unknown Source는 fileName과 lineNumber를 null로 변환함")
    fun givenUnknownSourceMatchResult_whenToStackTraceFrame_thenReturnsNullFileNameAndLineNumber() {
        // given
        val matchResult = frameRegex.find("    at com.example.FooService.doWork(Unknown Source)")
            ?: error("Expected stack trace frame to match")

        // when
        val frame = matchResult.toStackTraceFrame()

        // then
        assertThat(frame.fileName).isNull()
        assertThat(frame.lineNumber).isNull()
    }
}
