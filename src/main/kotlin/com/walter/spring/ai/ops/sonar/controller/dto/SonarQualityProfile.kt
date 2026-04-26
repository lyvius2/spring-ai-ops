package com.walter.spring.ai.ops.sonar.controller.dto

data class SonarQualityProfile(
    val key: String,
    val name: String,
    val language: String,
    val languageName: String,
    val isDefault: Boolean,
    val activeRuleCount: Int
)
