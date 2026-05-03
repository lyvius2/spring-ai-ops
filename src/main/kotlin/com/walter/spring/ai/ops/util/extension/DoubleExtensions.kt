package com.walter.spring.ai.ops.util.extension

private const val BYTES_PER_MB = 1024.0 * 1024.0

fun Double?.percentOf(total: Double?): Double? =
    if (this != null && total != null && total > 0.0) {
        (this / total * 100.0).coerceIn(0.0, 100.0)
    } else {
        null
    }

fun Double.bytesToMb(): Double = this / BYTES_PER_MB

