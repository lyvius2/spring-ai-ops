package com.walter.spring.ai.ops.controller.dto
data class GitRemoteStatusResponse(
    val githubTokenConfigured: Boolean,
    val githubPropertyConfigured: Boolean,
    val gitlabTokenConfigured: Boolean,
    val gitlabPropertyConfigured: Boolean,
    val currentProvider: String?,
    val githubUrl: String,
    val gitlabUrl: String,
)
