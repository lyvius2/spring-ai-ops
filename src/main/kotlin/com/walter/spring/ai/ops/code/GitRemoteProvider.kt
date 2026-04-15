package com.walter.spring.ai.ops.code

enum class GitRemoteProvider(
    val domain: String,
    val apiUrl: String,
) {
    GITHUB(
        domain = "github.com",
        apiUrl = "https://api.github.com",
    ),
    GITLAB(
        domain = "gitlab.com",
        apiUrl = "https://gitlab.com/api/v4",
    )
}