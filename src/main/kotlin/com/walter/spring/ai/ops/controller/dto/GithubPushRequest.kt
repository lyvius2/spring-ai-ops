package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.walter.spring.ai.ops.code.GitHubConstants.Companion.EMPTY_SHA

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPushRequest(
    val before: String = "",
    val after: String = "",
    val repository: GithubPushRepository = GithubPushRepository(),
    val commits: List<GithubPushCommit> = emptyList(),
) {
    fun isNewBranch(): Boolean = before == EMPTY_SHA
}
