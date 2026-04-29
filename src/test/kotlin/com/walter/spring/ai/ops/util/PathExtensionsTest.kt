package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.service.dto.StackTraceFrame
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PathExtensionsTest {

    @TempDir
    lateinit var repositoryRoot: Path

    @Test
    @DisplayName("패키지 경로와 fileName이 일치하면 direct path로 source file을 찾음")
    fun givenDirectSourcePath_whenResolveSourceFile_thenReturnsMatchedFile() {
        // given
        val sourceFile = repositoryRoot.resolve("src/main/kotlin/com/example/service/FooService.kt")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package com.example.service

            class FooService
            """.trimIndent()
        )
        val frame = StackTraceFrame("com.example.service.FooService", "doWork", "FooService.kt", 3)

        // when
        val resolvedFile = repositoryRoot.resolveSourceFile(frame)

        // then
        assertThat(resolvedFile).isEqualTo(sourceFile)
    }

    @Test
    @DisplayName("direct path가 없으면 multi-module 구조에서 fileName과 package 선언으로 source file을 찾음")
    fun givenMultiModuleSourcePath_whenResolveSourceFile_thenReturnsPackageMatchedFile() {
        // given
        val wrongPackageFile = repositoryRoot.resolve("domain/src/main/kotlin/com/other/FooService.kt")
        val matchedPackageFile = repositoryRoot.resolve("service-a/src/main/kotlin/com/example/service/FooService.kt")
        wrongPackageFile.parent.createDirectories()
        matchedPackageFile.parent.createDirectories()
        wrongPackageFile.writeText(
            """
            package com.other

            class FooService
            """.trimIndent()
        )
        matchedPackageFile.writeText(
            """
            package com.example.service

            class FooService
            """.trimIndent()
        )
        val frame = StackTraceFrame("com.example.service.FooService", "doWork", "FooService.kt", 3)

        // when
        val resolvedFile = repositoryRoot.resolveSourceFile(frame)

        // then
        assertThat(resolvedFile).isEqualTo(matchedPackageFile)
    }

    @Test
    @DisplayName("fileName이 없으면 simpleClassName 기반 kt/java 후보로 source file을 찾음")
    fun givenFrameWithoutFileName_whenResolveSourceFile_thenUsesSimpleClassNameFallback() {
        // given
        val sourceFile = repositoryRoot.resolve("src/main/java/com/example/service/FooService.java")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package com.example.service;

            public class FooService {}
            """.trimIndent()
        )
        val frame = StackTraceFrame("com.example.service.FooService", "doWork", null, 3)

        // when
        val resolvedFile = repositoryRoot.resolveSourceFile(frame)

        // then
        assertThat(resolvedFile).isEqualTo(sourceFile)
    }

    @Test
    @DisplayName("lineNumber가 있으면 focus line 전후 radius 범위의 snippet을 추출하고 focus line을 표시함")
    fun givenSourceFileWithFocusLine_whenExtractSourceSnippet_thenReturnsFocusedSnippet() {
        // given
        val sourceFile = repositoryRoot.resolve("src/main/kotlin/com/example/service/FooService.kt")
        sourceFile.parent.createDirectories()
        sourceFile.writeText((1..20).joinToString(System.lineSeparator()) { "line $it" })
        val frame = StackTraceFrame("com.example.service.FooService", "doWork", "FooService.kt", 10)

        // when
        val snippet = sourceFile.extractSourceSnippet(repositoryRoot, frame, radius = 2)

        // then
        assertThat(snippet.filePath).isEqualTo("src/main/kotlin/com/example/service/FooService.kt")
        assertThat(snippet.startLine).isEqualTo(8)
        assertThat(snippet.endLine).isEqualTo(12)
        assertThat(snippet.focusLine).isEqualTo(10)
        assertThat(snippet.content).contains("   8 | line 8")
        assertThat(snippet.content).contains(">>   10 | line 10")
        assertThat(snippet.content).contains("  12 | line 12")
    }

    @Test
    @DisplayName("lineNumber가 없으면 파일 상단 최대 80줄 snippet을 추출함")
    fun givenFrameWithoutLineNumber_whenExtractSourceSnippet_thenReturnsTopSection() {
        // given
        val sourceFile = repositoryRoot.resolve("src/main/kotlin/com/example/service/FooService.kt")
        sourceFile.parent.createDirectories()
        sourceFile.writeText((1..100).joinToString(System.lineSeparator()) { "line $it" })
        val frame = StackTraceFrame("com.example.service.FooService", "doWork", "FooService.kt", null)

        // when
        val snippet = sourceFile.extractSourceSnippet(repositoryRoot, frame)

        // then
        assertThat(snippet.startLine).isEqualTo(1)
        assertThat(snippet.endLine).isEqualTo(80)
        assertThat(snippet.focusLine).isNull()
        assertThat(snippet.content).contains("   1 | line 1")
        assertThat(snippet.content).contains("  80 | line 80")
        assertThat(snippet.content).doesNotContain("line 81")
    }

    @Test
    @DisplayName("source file을 찾지 못하면 null을 반환함")
    fun givenNoMatchingSourceFile_whenResolveSourceFile_thenReturnsNull() {
        // given
        Files.createDirectories(repositoryRoot.resolve("src/main/kotlin"))
        val frame = StackTraceFrame("com.example.service.MissingService", "doWork", "MissingService.kt", 10)

        // when
        val resolvedFile = repositoryRoot.resolveSourceFile(frame)

        // then
        assertThat(resolvedFile).isNull()
    }
}
