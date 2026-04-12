package com.walter.spring.ai.ops.connector.dto

import com.walter.spring.ai.ops.controller.dto.GithubPushRequest

data class GithubDifferInquiry(
    val owner: String,
    val repo: String,
    val base: String,
    val head: String,
) {
    companion object {
        fun of(request: GithubPushRequest): GithubDifferInquiry {
            return GithubDifferInquiry(
                owner = request.repository.owner.login,
                repo = request.repository.name,
                base = request.before,
                head = request.after,
            )
        }
    }
}
