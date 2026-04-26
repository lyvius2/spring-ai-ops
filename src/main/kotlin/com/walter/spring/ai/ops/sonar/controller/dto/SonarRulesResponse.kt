package com.walter.spring.ai.ops.sonar.controller.dto

data class SonarRulesResponse(
    val total: Int,
    val p: Int,
    val ps: Int,
    val rules: List<Map<String, Any>>,
    val actives: Map<String, Any> = emptyMap()
)
