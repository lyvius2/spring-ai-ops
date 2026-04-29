package com.walter.spring.ai.ops.service.dto

data class AppGitConfig(
    val gitUrl: String?,
    val deployBranch: String?,
) {
    fun isValidConfig(): Boolean = !gitUrl.isNullOrBlank() && !deployBranch.isNullOrBlank()
}
