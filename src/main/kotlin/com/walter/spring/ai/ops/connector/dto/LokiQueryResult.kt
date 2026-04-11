package com.walter.spring.ai.ops.connector.dto

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class LokiQueryResult(
    val status: String = "",
    val data: LokiData? = null,
    val errorMessage: String = "",
) {
    fun createLogSectionPrompt(): String {
        val logResults = this
        val logSection = buildString {
            appendLine("## Application Logs")
            val streams = logResults.data?.result ?: emptyList()
            if (streams.isEmpty() || streams.all { it.values.isEmpty() }) {
                appendLine("(No logs available for the given time range and label selector.)")
            } else {
                streams.forEach { stream ->
                    if (stream.stream.isNotEmpty()) {
                        appendLine("### Stream Labels: ${stream.stream.entries.joinToString { "${it.key}=${it.value}" }}")
                    }
                    stream.values.forEach { entry ->
                        val timestamp = entry.getOrNull(0)
                            ?.toLongOrNull()
                            ?.let { nanos ->
                                Instant.ofEpochMilli(nanos / 1_000_000)
                                    .atOffset(ZoneOffset.UTC)
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
                            } ?: ""
                        val logLine = entry.getOrNull(1) ?: ""
                        appendLine("[$timestamp] $logLine")
                    }
                }
            }
        }
        return logSection
    }
}