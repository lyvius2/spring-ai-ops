package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.code.PullRequestAction
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Normalised pull request / merge request webhook payload received from GitHub or GitLab")
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPullRequestRequest(
    @Schema(description = "Normalised action: OPENED (opened/reopened/ready_for_review), SYNCHRONIZE (new commits pushed to PR head), or IGNORED")
    val action: PullRequestAction = PullRequestAction.IGNORED,
    @Schema(description = "PR number (GitHub) or MR iid (GitLab)", example = "42")
    val number: Int = 0,
    @Schema(description = "PR title", example = "Add pull request review feature")
    val title: String = "",
    @Schema(description = "Start branch SHA — the commit where the PR/MR was created from (GitLab start_sha / GitHub `base`)", example = "abc1234")
    val startSha: String = "",
    @Schema(description = "Base branch SHA — the target of the PR/MR", example = "abc1234")
    val baseSha: String = "",
    @Schema(description = "Head branch SHA — the tip of the PR/MR source branch", example = "def5678")
    val headSha: String = "",
    @Schema(description = "Previous head SHA before a synchronize event (GitLab oldrev / GitHub `before`)", example = "xyz9999")
    val beforeSha: String = "",
    @Schema(description = "Whether the PR is a draft / work-in-progress")
    val draft: Boolean = false,
    @Schema(description = "Repository information")
    val repository: GithubPushRepository = GithubPushRepository(),
    @Schema(description = "Human-facing URL of the PR/MR", example = "https://github.com/octocat/repo/pull/42")
    val htmlUrl: String = "",
    @JsonIgnore val source: GitRemoteProvider = GitRemoteProvider.GITHUB,
) {
    fun isReviewTrigger(): Boolean = action == PullRequestAction.OPENED || action == PullRequestAction.SYNCHRONIZE

    companion object {
        /**
         * Maps a GitHub `pull_request` webhook body to [GithubPullRequestRequest].
         *
         * Key fields:
         * - `action`, `number`
         * - `pull_request.title`, `pull_request.html_url`, `pull_request.draft`
         * - `pull_request.base.sha`, `pull_request.head.sha`, `before` (only present on synchronize)
         * - `repository.name`, `repository.html_url`, `repository.owner.login`
         */
        @Suppress("UNCHECKED_CAST")
        fun fromGithubBody(body: Map<String, Any>): GithubPullRequestRequest {
            val rawAction = body["action"] as? String ?: ""
            val pr = body["pull_request"] as? Map<String, Any> ?: emptyMap()
            val base = pr["base"] as? Map<String, Any> ?: emptyMap()
            val head = pr["head"] as? Map<String, Any> ?: emptyMap()
            val repoMap = body["repository"] as? Map<String, Any> ?: emptyMap()
            val ownerMap = repoMap["owner"] as? Map<String, Any> ?: emptyMap()
            return GithubPullRequestRequest(
                action = PullRequestAction.fromGithub(rawAction),
                number = (body["number"] as? Number)?.toInt() ?: (pr["number"] as? Number)?.toInt() ?: 0,
                title = pr["title"] as? String ?: "",
                baseSha = base["sha"] as? String ?: "",
                headSha = head["sha"] as? String ?: "",
                beforeSha = body["before"] as? String ?: "",
                draft = pr["draft"] as? Boolean ?: false,
                repository = GithubPushRepository(
                    name = repoMap["name"] as? String ?: "",
                    owner = GithubPushOwner(login = ownerMap["login"] as? String ?: ""),
                    htmlUrl = repoMap["html_url"] as? String ?: "",
                ),
                htmlUrl = pr["html_url"] as? String ?: "",
                source = GitRemoteProvider.GITHUB,
            )
        }

        /**
         * Maps a GitLab `Merge Request Hook` webhook body to [GithubPullRequestRequest].
         *
         * Key fields:
         * - `object_attributes.action`, `object_attributes.iid`, `object_attributes.title`, `object_attributes.url`
         * - `object_attributes.work_in_progress` (draft equivalent)
         * - `object_attributes.oldrev` (previous head SHA on update)
         * - `object_attributes.last_commit.id` (current head SHA)
         * - `object_attributes.target.default_branch` / diff_refs.base_sha for base
         * - `project.name`, `project.web_url`, `project.path_with_namespace`
         */
        @Suppress("UNCHECKED_CAST")
        fun fromGitlabBody(body: Map<String, Any>): GithubPullRequestRequest {
            val attrs = body["object_attributes"] as? Map<String, Any> ?: emptyMap()
            val rawAction = attrs["action"] as? String ?: ""
            val oldrev = attrs["oldrev"] as? String ?: ""
            val lastCommit = attrs["last_commit"] as? Map<String, Any> ?: emptyMap()
            val diffRefs = attrs["diff_refs"] as? Map<String, Any> ?: emptyMap()
            val projectMap = body["project"] as? Map<String, Any> ?: emptyMap()
            val pathWithNamespace = projectMap["path_with_namespace"] as? String ?: ""
            val ownerLogin = pathWithNamespace.substringBefore("/")
            return GithubPullRequestRequest(
                action = PullRequestAction.fromGitlab(rawAction, hasOldrev = oldrev.isNotBlank()),
                number = (attrs["iid"] as? Number)?.toInt() ?: 0,
                title = attrs["title"] as? String ?: "",
                baseSha = diffRefs["base_sha"] as? String ?: "",
                headSha = lastCommit["id"] as? String ?: diffRefs["head_sha"] as? String ?: "",
                beforeSha = oldrev,
                draft = attrs["work_in_progress"] as? Boolean ?: false,
                repository = GithubPushRepository(
                    name = projectMap["name"] as? String ?: "",
                    owner = GithubPushOwner(login = ownerLogin),
                    htmlUrl = projectMap["web_url"] as? String ?: "",
                ),
                htmlUrl = attrs["url"] as? String ?: "",
                source = GitRemoteProvider.GITLAB,
            )
        }
    }
}