package com.walter.spring.ai.ops.controller.dto

data class GithubTokenSaveResponse(
    val success: Boolean,
    val message: String = "",
) {
    companion object {
        fun success() = GithubTokenSaveResponse(true)
        fun failure(message: String) = GithubTokenSaveResponse(false, message)
    }
}
