package com.walter.spring.ai.ops.util.extension

import java.time.LocalDateTime
import java.time.OffsetDateTime

fun String.toISO8601(): LocalDateTime? {
    return try {
        OffsetDateTime.parse(this).toLocalDateTime()
    } catch (_: Exception) {
        null
    }
}