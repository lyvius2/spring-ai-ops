package com.walter.spring.ai.ops.controller.dto
data class GitRemoteConfigResponse(
    val success: Boolean,
    val message: String = "",
) {
    companion object {
        fun success() = GitRemoteConfigResponse(success = true)
        fun failure(message: String) = GitRemoteConfigResponse(success = false, message = message)
    }
}
