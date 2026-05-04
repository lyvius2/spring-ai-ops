package com.walter.spring.ai.ops.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Remove admin accounts request")
data class RemoveAdminsRequest(
    @Schema(description = "Usernames to remove")
    val usernames: List<String>,
)