package com.walter.spring.ai.ops.util

import java.time.LocalDateTime
import java.time.OffsetDateTime

fun LocalDateTime.fromISO8601(dateTimeString: String): LocalDateTime? {
    return try {
        OffsetDateTime.parse(dateTimeString).toLocalDateTime()
    } catch (_: Exception) {
        null
    }
}