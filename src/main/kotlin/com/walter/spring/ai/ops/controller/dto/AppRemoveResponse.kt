package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Response for application removal operation")
data class AppRemoveResponse(
    @Schema(description = "Whether the removal was successful")
    val success: Boolean
) {
    companion object {
        fun success() = AppRemoveResponse(true)
    }
}
