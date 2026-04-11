package com.walter.spring.ai.ops.controller.dto

data class AppAddResponse(
    val success: Boolean,
    val message: String,
) {
    companion object {
        fun success() = AppAddResponse(true, "Application registered successfully.")
        fun failure(e: Exception) = AppAddResponse(false, e.message ?: "Failed to register application.")
    }
}
