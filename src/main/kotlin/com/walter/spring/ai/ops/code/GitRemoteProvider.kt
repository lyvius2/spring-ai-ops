package com.walter.spring.ai.ops.code

enum class GitRemoteProvider(
    val domain: String,
    val apiUrl: String,
    val alertMessage: String,
) {
    GITHUB(
        domain = "github.com",
        apiUrl = "https://api.github.com",
        alertMessage = "[CREDENTIAL_ERROR] GitHub access token is not configured. " +
                "Please set your GitHub personal access token via the Git Remote Configuration."
    ),
    GITLAB(
        domain = "gitlab.com",
        apiUrl = "https://gitlab.com/api/v4",
        alertMessage = "[CREDENTIAL_ERROR] GitLab access token is not configured. " +
                "Please set your GitLab personal access token via the Git Remote Configuration."
    )
}