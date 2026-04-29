package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.connector.dto.LokiData
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.connector.dto.LokiStream
import com.walter.spring.ai.ops.util.StackTraceParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class IncidentSourceContextServiceTest {

    @TempDir
    lateinit var repositoryRoot: Path

    private val service = IncidentSourceContextService(StackTraceParser())

    @Test
    @DisplayName("Loki 로그의 stack trace frame을 source snippet으로 변환함")
    fun givenLokiLogsAndRepository_whenCreateContext_thenReturnsResolvedSourceSnippet() {
        // given
        val sourceFile = repositoryRoot.resolve("src/main/kotlin/com/example/service/FooService.kt")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package com.example.service

            class FooService {
                fun doWork() {
                    error("failed")
                }
            }
            """.trimIndent()
        )
        val logResults = lokiQueryResult(
            """
            java.lang.IllegalStateException: failed
                at com.example.service.FooService.doWork(FooService.kt:5)
                at org.springframework.web.method.HandlerMethod.invoke(HandlerMethod.java:95)
            """.trimIndent()
        )

        // when
        val context = service.createContext(logResults, repositoryRoot)

        // then
        assertThat(context.frames).hasSize(1)
        assertThat(context.frames[0].className).isEqualTo("com.example.service.FooService")
        assertThat(context.snippets).hasSize(1)
        assertThat(context.snippets[0].filePath).isEqualTo("src/main/kotlin/com/example/service/FooService.kt")
        assertThat(context.snippets[0].focusLine).isEqualTo(5)
        assertThat(context.snippets[0].content).contains(">>    5 |         error(\"failed\")")
        assertThat(context.unresolvedFrames).isEmpty()
    }

    @Test
    @DisplayName("repositoryRoot가 없으면 frame만 반환하고 모든 frame을 unresolved로 처리함")
    fun givenNoRepositoryRoot_whenCreateContext_thenReturnsFramesAsUnresolved() {
        // given
        val logResults = lokiQueryResult(
            "    at com.example.service.FooService.doWork(FooService.kt:5)"
        )

        // when
        val context = service.createContext(logResults, null)

        // then
        assertThat(context.frames).hasSize(1)
        assertThat(context.snippets).isEmpty()
        assertThat(context.unresolvedFrames).containsExactlyElementsOf(context.frames)
    }

    @Test
    @DisplayName("source file을 찾지 못한 frame은 unresolvedFrames에 포함함")
    fun givenMissingSourceFile_whenCreateContext_thenReturnsUnresolvedFrame() {
        // given
        val logResults = lokiQueryResult(
            "    at com.example.service.MissingService.doWork(MissingService.kt:5)"
        )

        // when
        val context = service.createContext(logResults, repositoryRoot)

        // then
        assertThat(context.frames).hasSize(1)
        assertThat(context.snippets).isEmpty()
        assertThat(context.unresolvedFrames).containsExactly(context.frames[0])
    }

    @Test
    @DisplayName("동일 source snippet은 중복 제거함")
    fun givenDuplicateFrames_whenCreateContext_thenDeduplicatesSourceSnippets() {
        // given
        val sourceFile = repositoryRoot.resolve("src/main/kotlin/com/example/service/FooService.kt")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package com.example.service

            class FooService {
                fun doWork() {
                    error("failed")
                }
            }
            """.trimIndent()
        )
        val logResults = lokiQueryResult(
            """
                at com.example.service.FooService.doWork(FooService.kt:5)
                at com.example.service.FooService.doWork(FooService.kt:5)
            """.trimIndent()
        )

        // when
        val context = service.createContext(logResults, repositoryRoot)

        // then
        assertThat(context.frames).hasSize(1)
        assertThat(context.snippets).hasSize(1)
        assertThat(context.unresolvedFrames).isEmpty()
    }

    private fun lokiQueryResult(logText: String): LokiQueryResult {
        return LokiQueryResult(
            data = LokiData(
                result = listOf(
                    LokiStream(
                        values = listOf(
                            listOf("1710000000000000000", logText)
                        )
                    )
                )
            )
        )
    }
}
