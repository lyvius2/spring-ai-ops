package com.walter.spring.ai.ops.service.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.walter.spring.ai.ops.code.DiffSide

@JsonIgnoreProperties(ignoreUnknown = true)
data class LlmInlineComment(
    val file: String = "",
    val line: Int = 0,
    val side: DiffSide = DiffSide.RIGHT,
    val body: String = "",
)