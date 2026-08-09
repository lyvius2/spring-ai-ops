package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.code.DiffSide
import com.walter.spring.ai.ops.service.dto.HunkLine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DiffHunkParserTest {

    @Test
    @DisplayName("빈 patch → 빈 ParsedFileDiff 반환")
    fun givenBlankPatch_whenParse_thenReturnsEmptyMapping() {
        // when
        val result = DiffHunkParser.parse("a.kt", "a.kt", "")

        // then
        assertThat(result.newPath).isEqualTo("a.kt")
        assertThat(result.oldPath).isEqualTo("a.kt")
        assertThat(result.lines).isEmpty()
    }

    @Test
    @DisplayName("추가 라인만 있으면 RIGHT 사이드로만 매핑")
    fun givenAdditionsOnly_whenParse_thenMapsRightSideOnly() {
        // given
        val patch = """
            @@ -0,0 +1,3 @@
            +line1
            +line2
            +line3
        """.trimIndent()

        // when
        val result = DiffHunkParser.parse("new.kt", "new.kt", patch)

        // then
        assertThat(result.lookup(1, DiffSide.RIGHT)).isEqualTo(HunkLine(DiffSide.RIGHT, 1, 1, null))
        assertThat(result.lookup(2, DiffSide.RIGHT)).isEqualTo(HunkLine(DiffSide.RIGHT, 2, 2, null))
        assertThat(result.lookup(3, DiffSide.RIGHT)).isEqualTo(HunkLine(DiffSide.RIGHT, 3, 3, null))
        assertThat(result.lookup(1, DiffSide.LEFT)).isNull()
    }

    @Test
    @DisplayName("삭제 라인만 있으면 LEFT 사이드로만 매핑")
    fun givenDeletionsOnly_whenParse_thenMapsLeftSideOnly() {
        // given
        val patch = """
            @@ -1,2 +0,0 @@
            -old1
            -old2
        """.trimIndent()

        // when
        val result = DiffHunkParser.parse("del.kt", "del.kt", patch)

        // then
        assertThat(result.lookup(1, DiffSide.LEFT)).isEqualTo(HunkLine(DiffSide.LEFT, 1, null, 1))
        assertThat(result.lookup(2, DiffSide.LEFT)).isEqualTo(HunkLine(DiffSide.LEFT, 2, null, 2))
        assertThat(result.lookup(1, DiffSide.RIGHT)).isNull()
    }

    @Test
    @DisplayName("컨텍스트 라인은 LEFT와 RIGHT 양쪽에 매핑되며 서로의 라인 번호를 참조")
    fun givenContextLine_whenParse_thenMapsBothSides() {
        // given
        val patch = """
            @@ -1,3 +1,3 @@
             ctx1
            -old
            +new
             ctx2
        """.trimIndent()

        // when
        val result = DiffHunkParser.parse("mix.kt", "mix.kt", patch)

        // then
        assertThat(result.lookup(1, DiffSide.RIGHT)).isEqualTo(HunkLine(DiffSide.RIGHT, 1, 1, 1))
        assertThat(result.lookup(1, DiffSide.LEFT)).isEqualTo(HunkLine(DiffSide.LEFT, 1, 1, 1))
        assertThat(result.lookup(2, DiffSide.LEFT)).isEqualTo(HunkLine(DiffSide.LEFT, 2, null, 2))
        assertThat(result.lookup(2, DiffSide.RIGHT)).isEqualTo(HunkLine(DiffSide.RIGHT, 2, 2, null))
        assertThat(result.lookup(3, DiffSide.RIGHT)).isEqualTo(HunkLine(DiffSide.RIGHT, 3, 3, 3))
        assertThat(result.lookup(3, DiffSide.LEFT)).isEqualTo(HunkLine(DiffSide.LEFT, 3, 3, 3))
    }

    @Test
    @DisplayName("여러 개 hunk가 있으면 각 헤더 기준으로 라인 카운터가 리셋됨")
    fun givenMultipleHunks_whenParse_thenResetsCountersPerHunk() {
        // given
        val patch = """
            @@ -1,2 +1,2 @@
             ctx
            -a
            +b
            @@ -50,2 +50,2 @@
             ctx
            -c
            +d
        """.trimIndent()

        // when
        val result = DiffHunkParser.parse("multi.kt", "multi.kt", patch)

        // then — first hunk
        assertThat(result.lookup(1, DiffSide.RIGHT)?.line).isEqualTo(1)
        assertThat(result.lookup(2, DiffSide.RIGHT)?.line).isEqualTo(2)
        // then — second hunk starts at line 50
        assertThat(result.lookup(50, DiffSide.RIGHT)).isNotNull()
        assertThat(result.lookup(51, DiffSide.RIGHT)?.newLine).isEqualTo(51)
        assertThat(result.lookup(51, DiffSide.LEFT)?.oldLine).isEqualTo(51)
    }

    @Test
    @DisplayName("count 생략된 헤더(@@ -1 +1 @@)도 파싱됨")
    fun givenHeaderWithoutCounts_whenParse_thenParsesCorrectly() {
        // given
        val patch = """
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent()

        // when
        val result = DiffHunkParser.parse("f.kt", "f.kt", patch)

        // then
        assertThat(result.lookup(1, DiffSide.LEFT)).isEqualTo(HunkLine(DiffSide.LEFT, 1, null, 1))
        assertThat(result.lookup(1, DiffSide.RIGHT)).isEqualTo(HunkLine(DiffSide.RIGHT, 1, 1, null))
    }

    @Test
    @DisplayName("\\ No newline at end of file 마커는 무시됨")
    fun givenNoNewlineMarker_whenParse_thenSkipsMarker() {
        // given
        val patch = """
            @@ -1,1 +1,1 @@
            -old
            \ No newline at end of file
            +new
            \ No newline at end of file
        """.trimIndent()

        // when
        val result = DiffHunkParser.parse("f.kt", "f.kt", patch)

        // then
        assertThat(result.lookup(1, DiffSide.LEFT)).isNotNull()
        assertThat(result.lookup(1, DiffSide.RIGHT)).isNotNull()
        assertThat(result.lines).hasSize(2)
    }

    @Test
    @DisplayName("hunk 헤더 이전의 파일 헤더(---/+++)는 무시됨")
    fun givenFileHeaders_whenParse_thenIgnored() {
        // given
        val patch = """
            --- a/file.kt
            +++ b/file.kt
            @@ -1,1 +1,1 @@
            -x
            +y
        """.trimIndent()

        // when
        val result = DiffHunkParser.parse("file.kt", "file.kt", patch)

        // then
        assertThat(result.lines).hasSize(2)
        assertThat(result.lookup(1, DiffSide.RIGHT)?.line).isEqualTo(1)
    }

    @Test
    @DisplayName("newPath와 oldPath가 다르면(rename) 그대로 보존")
    fun givenDifferentPaths_whenParse_thenPreservesBothPaths() {
        // given
        val patch = """
            @@ -1,1 +1,1 @@
            -old
            +new
        """.trimIndent()

        // when
        val result = DiffHunkParser.parse("new/path.kt", "old/path.kt", patch)

        // then
        assertThat(result.newPath).isEqualTo("new/path.kt")
        assertThat(result.oldPath).isEqualTo("old/path.kt")
    }
}