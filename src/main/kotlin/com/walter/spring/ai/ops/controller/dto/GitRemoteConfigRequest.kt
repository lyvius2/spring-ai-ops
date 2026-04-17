package com.walter.spring.ai.ops.controller.dto

data class GitRemoteConfigRequest(
    val provider: String = "",
    val token: String = "",
    val url: String = "",
)

