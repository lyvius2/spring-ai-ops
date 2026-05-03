package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.service.dto.StackTraceFrame
import com.walter.spring.ai.ops.util.extension.toStackTraceFrame
import org.springframework.stereotype.Component

@Component
class StackTraceParser {
    companion object {
        private const val DEFAULT_MAX_FRAME_COUNT = 5
    }

    private val frameRegex = Regex("""\s*at\s+([\w.$]+)\.([\w$<>]+)\(([^():]+)(?::(\d+))?\)""")

    private val excludedClassPrefixes = listOf(
        "java.",
        "javax.",
        "jakarta.",
        "jdk.",
        "kotlin.",
        "kotlinx.",
        "org.springframework.",
        "org.hibernate.",
        "org.apache.",
        "org.slf4j.",
        "ch.qos.logback.",
        "com.fasterxml.",
        "reactor.",
        "io.netty.",
        "io.micrometer.",
        "com.zaxxer.",
    )

    fun parse(logText: String, maxFrameCount: Int = DEFAULT_MAX_FRAME_COUNT): List<StackTraceFrame> {
        if (logText.isBlank() || maxFrameCount <= 0) {
            return emptyList()
        }

        return frameRegex.findAll(logText)
            .map { matchResult -> matchResult.toStackTraceFrame() }
            .filterNot { frame -> isExternalLibraryFrame(frame) }
            .distinctBy { frame -> "${frame.className}.${frame.methodName}:${frame.fileName}:${frame.lineNumber}" }
            .take(maxFrameCount)
            .toList()
    }

    private fun isExternalLibraryFrame(frame: StackTraceFrame): Boolean {
        return excludedClassPrefixes.any { prefix -> frame.className.startsWith(prefix) }
    }
}
