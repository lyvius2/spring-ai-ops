package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPushRequest(
    val before: String = "",
    val after: String = "",
    val repository: GithubPushRepository = GithubPushRepository(),
    val commits: List<GithubPushCommit> = emptyList(),
) {
    companion object {
        const val EMPTY_SHA = "0000000000000000000000000000000000000000"
    }

    fun isNewBranch(): Boolean = before == EMPTY_SHA
}
