package com.walter.spring.ai.ops.controller.dto

data class AppUpdateRequest(
    val name: String,
    val gitUrl: String? = null
)