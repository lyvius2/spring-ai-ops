package com.walter.spring.ai.ops.connector.dto

import com.walter.spring.ai.ops.controller.dto.GithubPushRequest

data class GitDifferInquiry(
    val owner: String,
    val repo: String,
    val base: String,
    val head: String,
) {
    val projectPath: String get() = "$owner/$repo"
    companion object {
        fun of(request: GithubPushRequest) = GitDifferInquiry(
            owner = request.repository.owner.login,
            repo = request.repository.name,
            base = request.before,
            head = request.after,
        )
    }
}

