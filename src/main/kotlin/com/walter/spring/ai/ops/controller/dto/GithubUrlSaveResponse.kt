package com.walter.spring.ai.ops.controller.dto

@ConsistentCopyVisibility
data class GithubUrlSaveResponse private constructor(
    val status: String,
    val message: String = "",
) {
    companion object {
        fun success(): GithubUrlSaveResponse = GithubUrlSaveResponse("OK")
        fun failure(message: String): GithubUrlSaveResponse = GithubUrlSaveResponse("ERROR", message)
    }
}
