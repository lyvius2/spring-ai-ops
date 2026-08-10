package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_TOKEN
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_URL
import com.walter.spring.ai.ops.connector.GitlabConnector
import com.walter.spring.ai.ops.connector.dto.GitCommentRequest
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GitlabCompareResult
import com.walter.spring.ai.ops.connector.dto.GitlabDiscussionPosition
import com.walter.spring.ai.ops.connector.dto.GitlabDiscussionRequest
import com.walter.spring.ai.ops.connector.dto.GitlabFile
import com.walter.spring.ai.ops.connector.dto.GitlabMergeRequestDiffRefs
import com.walter.spring.ai.ops.controller.dto.GithubPullRequestRequest
import com.walter.spring.ai.ops.service.dto.LlmInlineComment
import com.walter.spring.ai.ops.service.dto.LlmInlineReviewResult
import com.walter.spring.ai.ops.service.dto.ParsedFileDiff
import com.walter.spring.ai.ops.util.CryptoProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class GitlabService(
    redisTemplate: StringRedisTemplate,
    objectMapper: ObjectMapper,
    cryptoProvider: CryptoProvider,
    private val gitlabConnector: GitlabConnector,
    @Value("\${analysis.data-retention-hours:120}") retentionHours: Long,
    @Value("\${analysis.maximum-view-count:5}") maximumViewCount: Long,
    @Value("\${gitlab.access-token:}") override val configuredToken: String,
    @Value("\${gitlab.url:https://gitlab.com/api/v4}") override val configuredUrl: String,
) : GitRemoteService(redisTemplate, objectMapper, cryptoProvider, retentionHours, maximumViewCount) {
    private val log = LoggerFactory.getLogger(GitlabService::class.java)

    override val redisTokenKey: String = REDIS_KEY_GITLAB_TOKEN
    override val redisUrlKey: String = REDIS_KEY_GITLAB_URL

    override fun executeInquiryDiffer(inquiry: GitDifferInquiry): GitCompareResult {
        // GitLab API requires '/' in project path to be encoded as '%2F' in path segments
        val encodedPath = inquiry.projectPath.replace("/", "%2F")
        return if (inquiry.base == EMPTY_SHA || inquiry.commitShas.size > 1) {
            getPushedCommits(encodedPath, inquiry)
        } else {
            val compareResult = gitlabConnector.compare(encodedPath, inquiry.base, inquiry.head)
            if (compareResult.hasError() || compareResult.diffs.isEmpty()) {
                if (compareResult.hasError()) {
                    log.warn("GitLab compare failed, falling back to pushed commits: head={}, error={}", inquiry.head, compareResult.errorMessage)
                } else {
                    log.warn("GitLab compare returned empty diffs, falling back to pushed commits: head={}", inquiry.head)
                }
                getPushedCommits(encodedPath, inquiry)
            } else {
                compareResult
            }
        }
    }

    private fun getPushedCommits(encodedPath: String, inquiry: GitDifferInquiry): GitlabCompareResult {
        val commitShas = inquiry.commitShas.ifEmpty { listOf(inquiry.head) }.distinct()
        val commits = commitShas.map { sha -> gitlabConnector.getCommit(encodedPath, sha) }
        val diffs = commitShas.flatMap { sha -> gitlabConnector.getCommitDiff(encodedPath, sha) }
        return GitlabCompareResult(
            commits = commits,
            diffs = mergeDiffs(diffs),
            errorMessage = commits.firstOrNull { it.errorMessage?.isNotBlank() == true }?.errorMessage ?: "",
        )
    }

    override fun postPullRequestComment(inquiry: GitDifferInquiry, number: Int, body: String) {
        if (!isTokenConfigured()) {
            log.warn("Skip GitLab MR note — token is not configured (projectPath={}, iid={})", inquiry.projectPath, number)
            return
        }
        if (body.isBlank()) {
            log.warn("Skip GitLab MR note — empty body (projectPath={}, iid={})", inquiry.projectPath, number)
            return
        }
        val encodedPath = inquiry.projectPath.replace("/", "%2F")
        val commentRequest = GitCommentRequest(formatPullRequestComment(body))
        runCatching { gitlabConnector.createMergeRequestNote(encodedPath, number, commentRequest) }
            .onSuccess { response -> log.info("Posted GitLab MR note: projectPath={}, iid={}, noteId={}", inquiry.projectPath, number, response.id) }
            .onFailure { log.error("Failed to post GitLab MR note: projectPath={}, iid={}, error={}", inquiry.projectPath, number, it.message, it) }
    }

    override fun postPullRequestInlineComments(inquiry: GitDifferInquiry, number: Int, review: LlmInlineReviewResult, compareResult: GitCompareResult): Boolean {
        if (!isTokenConfigured()) {
            log.warn("Skip GitLab inline review — token is not configured (projectPath={}, iid={})", inquiry.projectPath, number)
            return false
        }
        if (inquiry.base.isBlank() || inquiry.head.isBlank()) {
            log.warn("Skip GitLab inline review — missing base/head SHA (projectPath={}, iid={})", inquiry.projectPath, number)
            return false
        }
        val parsedDiffs = compareResult.parseDiffs()
        val filtered = review.comments.filter { it.body.isNotBlank() && parsedDiffs[it.file]?.lookup(it.line, it.side) != null }
        val dropped = review.comments.size - filtered.size
        if (dropped > 0) {
            log.info("GitLab inline review — dropped {} of {} LLM comments outside diff (iid={})", dropped, review.comments.size, number)
        }
        if (filtered.isEmpty()) {
            log.warn("GitLab inline review — no valid inline comments after diff filtering (iid={})", number)
            return false
        }
        val encodedPath = inquiry.projectPath.replace("/", "%2F")
        val successCount = filtered.count { comment -> postSingleDiscussion(inquiry, encodedPath, number, comment, parsedDiffs) }
        if (successCount == 0) {
            log.warn("GitLab inline review — all {} discussions failed (projectPath={}, iid={})", filtered.size, inquiry.projectPath, number)
            return false
        }
        log.info("Posted GitLab inline review: projectPath={}, iid={}, succeeded={}/{}", inquiry.projectPath, number, successCount, filtered.size)
        val summaryBody = review.summary.ifBlank { "AI incremental review" }
        runCatching { gitlabConnector.createMergeRequestNote(encodedPath, number, GitCommentRequest(summaryBody)) }
            .onFailure { log.warn("Failed to post GitLab summary note alongside inline review: projectPath={}, iid={}, error={}", inquiry.projectPath, number, it.message) }
        return true
    }

    private fun postSingleDiscussion(inquiry: GitDifferInquiry, encodedPath: String, number: Int, comment: LlmInlineComment, parsedDiffs: Map<String, ParsedFileDiff>): Boolean {
        val parsedDiff = parsedDiffs[comment.file] ?: return false
        val hunkLine = parsedDiff.lookup(comment.line, comment.side) ?: return false
        val position = GitlabDiscussionPosition.of(inquiry, parsedDiff, hunkLine, inquiry.base)
        val request = GitlabDiscussionRequest(body = comment.body, position = position)
        val response = runCatching { gitlabConnector.createMrDiscussion(encodedPath, number, request) }
            .getOrElse {
                log.warn("GitLab discussion failed: projectPath={}, iid={}, file={}, line={}, error={}", inquiry.projectPath, number, comment.file, comment.line, it.message)
                return false
            }
        if (!response.errorMessage.isNullOrBlank()) {
            log.warn("GitLab discussion rejected: projectPath={}, iid={}, file={}, line={}, error={}", inquiry.projectPath, number, comment.file, comment.line, response.errorMessage)
            return false
        }
        return true
    }

    override fun resolveMissingPullRequestRefs(request: GithubPullRequestRequest): GithubPullRequestRequest {
        if (request.baseSha.isNotBlank() && request.headSha.isNotBlank()) {
            return request
        }
        if (request.number <= 0) {
            return request
        }
        val owner = request.repository.owner.login
        val repo = request.repository.name
        if (owner.isBlank() || repo.isBlank()) {
            return request
        }
        val diffRefs = fetchMergeRequestDiffRefs("$owner/$repo", request.number) ?: return request
        val resolved = request.copy(
            baseSha = request.baseSha.ifBlank { diffRefs.baseSha },
            headSha = request.headSha.ifBlank { diffRefs.headSha },
            startSha = request.startSha.ifBlank { diffRefs.startSha },
        )
        if (resolved.baseSha != request.baseSha || resolved.headSha != request.headSha) {
            log.info(
                "GitLab MR refs resolved via API: projectPath={}, iid={}, base={}, head={}",
                "$owner/$repo", request.number, resolved.baseSha, resolved.headSha,
            )
        }
        return resolved
    }

    private fun fetchMergeRequestDiffRefs(projectPath: String, iid: Int): GitlabMergeRequestDiffRefs? {
        val encodedPath = projectPath.replace("/", "%2F")
        val detail = runCatching { gitlabConnector.getMergeRequest(encodedPath, iid) }
            .getOrElse {
                log.warn("Failed to fetch GitLab MR detail: projectPath={}, iid={}, error={}", projectPath, iid, it.message)
                return null
            }
        if (!detail.errorMessage.isNullOrBlank()) {
            log.warn("GitLab MR detail returned error: projectPath={}, iid={}, error={}", projectPath, iid, detail.errorMessage)
            return null
        }
        return detail.diffRefs
    }

    private fun mergeDiffs(diffs: List<GitlabFile>): List<GitlabFile> =
        diffs.groupBy { it.newPath }
            .values
            .map { sameFileDiffs ->
                val latest = sameFileDiffs.last()
                latest.copy(
                    newFile = sameFileDiffs.any { it.newFile } && !latest.deletedFile,
                    renamedFile = sameFileDiffs.any { it.renamedFile },
                    diff = sameFileDiffs.mapNotNull { it.diff.takeIf(String::isNotBlank) }.joinToString("\n"),
                )
            }
}
