package com.walter.spring.ai.ops.controller.dto

data class ErrorResponse(
    val status: Int,
    val message: String,
    val path: String,
)
