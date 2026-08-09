package com.walter.spring.ai.ops.service.dto

import com.walter.spring.ai.ops.code.DiffSide

data class HunkLine(
    val side: DiffSide,
    val line: Int,
    val newLine: Int?,
    val oldLine: Int?,
)