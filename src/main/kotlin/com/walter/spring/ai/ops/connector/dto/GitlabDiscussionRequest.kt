package com.walter.spring.ai.ops.connector.dto

data class GitlabDiscussionRequest(
    val body: String,
    val position: GitlabDiscussionPosition,
)