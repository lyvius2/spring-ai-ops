package com.walter.spring.ai.ops.sonar.controller.dto

data class SonarPluginsResponse(
    val plugins: List<SonarPlugin> = emptyList()
)