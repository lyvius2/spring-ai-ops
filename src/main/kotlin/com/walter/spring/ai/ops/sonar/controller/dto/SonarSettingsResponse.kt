package com.walter.spring.ai.ops.sonar.controller.dto

data class SonarSettingsResponse(
    val settings: List<Map<String, Any>> = emptyList()
)
