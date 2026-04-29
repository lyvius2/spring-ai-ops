package com.walter.spring.ai.ops.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StackTraceParserTest {

    private val stackTraceParser = StackTraceParser()

    @Test
    @DisplayName("JVM stack trace frame에서 class, method, file, line을 파싱함")
    fun givenJvmStackTraceFrames_whenParse_thenReturnsParsedFrames() {
        // given
        val logText = """
            java.lang.IllegalStateException: failed
                at com.example.service.FooService.doWork(FooService.kt:42)
                at com.example.controller.FooController.handle(FooController.java:19)
        """.trimIndent()

        // when
        val frames = stackTraceParser.parse(logText)

        // then
        assertThat(frames).hasSize(2)
        assertThat(frames[0].className).isEqualTo("com.example.service.FooService")
        assertThat(frames[0].methodName).isEqualTo("doWork")
        assertThat(frames[0].fileName).isEqualTo("FooService.kt")
        assertThat(frames[0].lineNumber).isEqualTo(42)
        assertThat(frames[1].className).isEqualTo("com.example.controller.FooController")
        assertThat(frames[1].methodName).isEqualTo("handle")
        assertThat(frames[1].fileName).isEqualTo("FooController.java")
        assertThat(frames[1].lineNumber).isEqualTo(19)
    }

    @Test
    @DisplayName("라인 번호가 없는 JVM stack trace frame도 파싱함")
    fun givenFrameWithoutLineNumber_whenParse_thenReturnsFrameWithNullLineNumber() {
        // given
        val logText = "    at com.example.service.FooService.doWork(FooService.kt)"

        // when
        val frames = stackTraceParser.parse(logText)

        // then
        assertThat(frames).hasSize(1)
        assertThat(frames[0].className).isEqualTo("com.example.service.FooService")
        assertThat(frames[0].methodName).isEqualTo("doWork")
        assertThat(frames[0].fileName).isEqualTo("FooService.kt")
        assertThat(frames[0].lineNumber).isNull()
    }

    @Test
    @DisplayName("외부 라이브러리 frame은 제외함")
    fun givenExternalLibraryFrames_whenParse_thenExcludesExternalFrames() {
        // given
        val logText = """
                at org.springframework.web.method.HandlerMethod.invoke(HandlerMethod.java:95)
                at kotlin.collections.CollectionsKt___CollectionsKt.map(Collections.kt:1547)
                at com.example.service.FooService.doWork(FooService.kt:42)
        """.trimIndent()

        // when
        val frames = stackTraceParser.parse(logText)

        // then
        assertThat(frames).hasSize(1)
        assertThat(frames[0].className).isEqualTo("com.example.service.FooService")
    }

    @Test
    @DisplayName("중복 frame은 제거하고 최대 5개만 반환함")
    fun givenDuplicateAndManyFrames_whenParse_thenReturnsTopFiveDistinctFrames() {
        // given
        val logText = """
                at com.example.service.FooService.doWork(FooService.kt:42)
                at com.example.service.FooService.doWork(FooService.kt:42)
                at com.example.service.BarService.doWork(BarService.kt:10)
                at com.example.service.BazService.doWork(BazService.kt:11)
                at com.example.service.QuxService.doWork(QuxService.kt:12)
                at com.example.service.QuuxService.doWork(QuuxService.kt:13)
                at com.example.service.CorgeService.doWork(CorgeService.kt:14)
        """.trimIndent()

        // when
        val frames = stackTraceParser.parse(logText)

        // then
        assertThat(frames).hasSize(5)
        assertThat(frames.map { it.className }).containsExactly(
            "com.example.service.FooService",
            "com.example.service.BarService",
            "com.example.service.BazService",
            "com.example.service.QuxService",
            "com.example.service.QuuxService",
        )
    }

    @Test
    @DisplayName("Unknown Source와 Native Method는 fileName을 null로 처리함")
    fun givenUnknownSourceOrNativeMethod_whenParse_thenReturnsNullFileName() {
        // given
        val logText = """
                at com.example.service.FooService.doWork(Unknown Source)
                at com.example.service.BarService.doWork(Native Method)
        """.trimIndent()

        // when
        val frames = stackTraceParser.parse(logText)

        // then
        assertThat(frames).hasSize(2)
        assertThat(frames[0].fileName).isNull()
        assertThat(frames[0].lineNumber).isNull()
        assertThat(frames[1].fileName).isNull()
        assertThat(frames[1].lineNumber).isNull()
    }
}
