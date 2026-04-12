package com.walter.spring.ai.ops.controller.dto

data class GithubPushResponse(
    val status: String,
) {
    companion object {
        fun accepted(): GithubPushResponse {
            return GithubPushResponse("ACCEPTED")
        }
    }
}
