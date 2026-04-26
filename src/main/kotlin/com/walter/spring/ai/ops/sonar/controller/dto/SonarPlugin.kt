package com.walter.spring.ai.ops.sonar.controller.dto

data class SonarPlugin(
    val key: String,
    val name: String,
    val version: String,
    val filename: String,
    val hash: String,
    val updatedAt: String
)