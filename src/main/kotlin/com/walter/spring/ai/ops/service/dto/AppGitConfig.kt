package com.walter.spring.ai.ops.service.dto

import com.fasterxml.jackson.annotation.JsonIgnore

data class AppGitConfig(
    val gitUrl: String?,
    val deployBranch: String?,
) {
    @JsonIgnore
    fun isValidConfig(): Boolean = !gitUrl.isNullOrBlank() && !deployBranch.isNullOrBlank()
}
