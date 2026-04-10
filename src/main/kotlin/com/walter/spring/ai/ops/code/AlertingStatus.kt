package com.walter.spring.ai.ops.code

enum class AlertingStatus(
    val description: String,
) {
    RESOLVED("resolved event received"),
    FIRING("firing event received"),
}