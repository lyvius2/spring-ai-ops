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

        /**
         * Maps a GitHub push webhook request body to [GithubPushRequest].
         *
         * Key fields:
         * - `before`, `after`
         * - `repository.name`, `repository.html_url`, `repository.owner.login`
         * - `commits[].id`, `commits[].message`, `commits[].url`, `commits[].timestamp`
         */
        @Suppress("UNCHECKED_CAST")
        fun fromGithubBody(body: Map<String, Any>): GithubPushRequest {
            val repoMap = body["repository"] as? Map<String, Any> ?: emptyMap()
            val ownerMap = repoMap["owner"] as? Map<String, Any> ?: emptyMap()
            val commitList = body["commits"] as? List<Map<String, Any>> ?: emptyList()
            return GithubPushRequest(
                before = body["before"] as? String ?: "",
                after = body["after"] as? String ?: "",
                repository = GithubPushRepository(
                    name = repoMap["name"] as? String ?: "",
                    owner = GithubPushOwner(login = ownerMap["login"] as? String ?: ""),
                    htmlUrl = repoMap["html_url"] as? String ?: "",
                ),
                commits = createPushCommit(commitList),
            )
        }

        /**
         * Maps a GitLab push webhook request body to [GithubPushRequest].
         *
         * Key fields:
         * - `before`, `after`
         * - `project.name`, `project.web_url`
         * - `project.path_with_namespace` ("owner/repo") → owner extracted from prefix
         * - `commits[].id`, `commits[].message`, `commits[].url`, `commits[].timestamp`
         */
        @Suppress("UNCHECKED_CAST")
        fun fromGitlabBody(body: Map<String, Any>): GithubPushRequest {
            val projectMap = body["project"] as? Map<String, Any> ?: emptyMap()
            val pathWithNamespace = projectMap["path_with_namespace"] as? String ?: ""
            val ownerLogin = pathWithNamespace.substringBefore("/")
            val commitList = body["commits"] as? List<Map<String, Any>> ?: emptyList()
            return GithubPushRequest(
                before = body["before"] as? String ?: "",
                after = body["after"] as? String ?: "",
                repository = GithubPushRepository(
                    name = projectMap["name"] as? String ?: "",
                    owner = GithubPushOwner(login = ownerLogin),
                    htmlUrl = projectMap["web_url"] as? String ?: "",
                ),
                commits = createPushCommit(commitList),
            )
        }

        private fun createPushCommit(commitList: List<Map<String, Any>>): List<GithubPushCommit> = commitList.map { c ->
            GithubPushCommit(
                id = c["id"] as? String ?: "",
                message = c["message"] as? String ?: "",
                url = c["url"] as? String ?: "",
                timestamp = c["timestamp"] as? String ?: "",
            )
        }
    }

    fun isNewBranch(): Boolean = before == EMPTY_SHA
}
