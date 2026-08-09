package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.code.DiffSide
import com.walter.spring.ai.ops.service.dto.HunkLine
import com.walter.spring.ai.ops.service.dto.ParsedFileDiff

class DiffHunkParser {
    companion object {
        private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")

        @JvmStatic
        fun parse(newPath: String, oldPath: String, patch: String): ParsedFileDiff {
            if (patch.isBlank()) {
                return ParsedFileDiff(newPath, oldPath, emptyMap())
            }
            val lines = mutableMapOf<Pair<Int, DiffSide>, HunkLine>()
            var curOld = 0
            var curNew = 0
            var inHunk = false
            patch.lineSequence().forEach { raw ->
                val header = HUNK_HEADER.matchEntire(raw)
                if (header != null) {
                    curOld = header.groupValues[1].toInt()
                    curNew = header.groupValues[3].toInt()
                    inHunk = true
                    return@forEach
                }
                if (!inHunk) return@forEach
                when {
                    raw.startsWith("+++") || raw.startsWith("---") -> Unit
                    raw.startsWith("\\") -> Unit
                    raw.startsWith("+") -> {
                        lines[curNew to DiffSide.RIGHT] = HunkLine(DiffSide.RIGHT, curNew, curNew, null)
                        curNew++
                    }
                    raw.startsWith("-") -> {
                        lines[curOld to DiffSide.LEFT] = HunkLine(DiffSide.LEFT, curOld, null, curOld)
                        curOld++
                    }
                    raw.startsWith(" ") || raw.isEmpty() -> {
                        lines[curNew to DiffSide.RIGHT] = HunkLine(DiffSide.RIGHT, curNew, curNew, curOld)
                        lines[curOld to DiffSide.LEFT] = HunkLine(DiffSide.LEFT, curOld, curNew, curOld)
                        curNew++
                        curOld++
                    }
                }
            }
            return ParsedFileDiff(newPath, oldPath, lines)
        }
    }
}