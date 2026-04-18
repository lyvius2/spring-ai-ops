package com.walter.spring.ai.ops.controller.dto
data class GitRemoteStatusResponse(
    val githubTokenConfigured: Boolean,
    val githubPropertyConfigured: Boolean,
    val gitlabTokenConfigured: Boolean,
    val gitlabPropertyConfigured: Boolean,
    val githubUrl: String,
    val gitlabUrl: String,
) {
    companion object {
        fun of(configMap: Map<String, Any?>): GitRemoteStatusResponse {
            return GitRemoteStatusResponse(
                githubTokenConfigured = configMap["githubTokenConfigured"] as? Boolean ?: false,
                githubPropertyConfigured = configMap["githubPropertyConfigured"] as? Boolean ?: false,
                gitlabTokenConfigured = configMap["gitlabTokenConfigured"] as? Boolean ?: false,
                gitlabPropertyConfigured = configMap["gitlabPropertyConfigured"] as? Boolean ?: false,
                githubUrl = configMap["githubUrl"] as? String ?: "",
                gitlabUrl = configMap["gitlabUrl"] as? String ?: "",
            )
        }
    }
}
