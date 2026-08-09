package com.walter.spring.ai.ops.service.dto

import com.walter.spring.ai.ops.code.DiffSide

data class ParsedFileDiff(
    val newPath: String,
    val oldPath: String,
    val lines: Map<Pair<Int, DiffSide>, HunkLine>,
) {
    fun lookup(line: Int, side: DiffSide): HunkLine? = lines[line to side]
}