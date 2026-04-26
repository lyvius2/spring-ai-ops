package com.walter.spring.ai.ops.sonar.controller.dto

data class SonarCeTask(
    val id: String,
    val type: String,
    val status: String,
    val projectId: String
)
