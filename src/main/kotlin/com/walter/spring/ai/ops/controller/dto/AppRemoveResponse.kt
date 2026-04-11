package com.walter.spring.ai.ops.controller.dto

data class AppRemoveResponse(
    val success: Boolean
) {
    companion object {
        fun success() = AppRemoveResponse(true)
    }
}
