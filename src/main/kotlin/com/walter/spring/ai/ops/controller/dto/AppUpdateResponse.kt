package com.walter.spring.ai.ops.controller.dto

data class AppUpdateResponse(
    val success: Boolean,
    val message: String
) {
    companion object {
        fun success() = AppUpdateResponse(true, "Application updated successfully.")
        fun failure(e: Exception) = AppUpdateResponse(false, e.message ?: "Failed to update application.")
    }
}