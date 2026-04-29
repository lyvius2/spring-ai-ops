package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.service.dto.StackTraceFrame

fun MatchResult.toStackTraceFrame(): StackTraceFrame {
    return StackTraceFrame(
        className = groupValues[1],
        methodName = groupValues[2],
        fileName = groupValues[3].takeIf { it != "Unknown Source" && it != "Native Method" },
        lineNumber = groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }?.toIntOrNull(),
    )
}
