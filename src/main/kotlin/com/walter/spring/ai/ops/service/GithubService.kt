package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_TOKEN
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_URL
import com.walter.spring.ai.ops.connector.GithubConnector
import com.walter.spring.ai.ops.connector.dto.GitCommentRequest
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubFile
import com.walter.spring.ai.ops.connector.dto.GithubReviewComment
import com.walter.spring.ai.ops.connector.dto.GithubReviewRequest
import com.walter.spring.ai.ops.service.dto.LlmInlineReviewResult
import com.walter.spring.ai.ops.util.CryptoProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class GithubService(
    redisTemplate: StringRedisTemplate,
    objectMapper: ObjectMapper,
    cryptoProvider: CryptoProvider,
    private val githubConnector: GithubConnector,
    @Value("\${analysis.data-retention-hours:120}") retentionHours: Long,
    @Value("\${analysis.maximum-view-count:5}") maximumViewCount: Long,
    @Value("\${github.access-token:}") override val configuredToken: String,
    @Value("\${github.url:https://api.github.com}") override val configuredUrl: String,
) : GitRemoteService(redisTemplate, objectMapper, cryptoProvider, retentionHours, maximumViewCount) {
    private val log = LoggerFactory.getLogger(GithubService::class.java)

    override val redisTokenKey: String = REDIS_KEY_GITHUB_TOKEN
    override val redisUrlKey: String = REDIS_KEY_GITHUB_URL

    override fun executeInquiryDiffer(inquiry: GitDifferInquiry): GitCompareResult {
        return if (inquiry.base == EMPTY_SHA || inquiry.commitShas.size > 1) {
            getPushedCommits(inquiry)
        } else {
            val compareResult = githubConnector.compare(inquiry.owner, inquiry.repo, "${inquiry.base}...${inquiry.head}")
            if (compareResult.hasError() || compareResult.files.isEmpty()) {
                if (compareResult.hasError()) {
                    log.warn("GitHub compare failed, falling back to pushed commits: head={}, error={}", inquiry.head, compareResult.errorMessage)
                } else {
                    log.warn("GitHub compare returned empty files (250-commit limit?), falling back to pushed commits: head={}", inquiry.head)
                }
                getPushedCommits(inquiry)
            } else {
                compareResult
            }
        }
    }

    private fun getPushedCommits(inquiry: GitDifferInquiry): GithubCompareResult {
        val commitShas = inquiry.commitShas.ifEmpty { listOf(inquiry.head) }.distinct()
        val commitResults = commitShas.map { sha -> githubConnector.getCommit(inquiry.owner, inquiry.repo, sha) }
        return GithubCompareResult(
            files = mergeFiles(commitResults.flatMap { it.files }),
            commits = commitResults.flatMap { it.commits },
            errorMessage = commitResults.firstOrNull { it.hasError() }?.errorMessage ?: "",
        )
    }

    override fun postPullRequestComment(inquiry: GitDifferInquiry, number: Int, body: String) {
        if (!isTokenConfigured()) {
            log.warn("Skip GitHub PR comment — token is not configured (owner={}, repo={}, number={})", inquiry.owner, inquiry.repo, number)
            return
        }
        if (body.isBlank()) {
            log.warn("Skip GitHub PR comment — empty body (owner={}, repo={}, number={})", inquiry.owner, inquiry.repo, number)
            return
        }
        val commentRequest = GitCommentRequest(formatPullRequestComment(body))
        runCatching { githubConnector.createIssueComment(inquiry.owner, inquiry.repo, number, commentRequest) }
            .onSuccess { response -> log.info("Posted GitHub PR comment: owner={}, repo={}, number={}, commentId={}", inquiry.owner, inquiry.repo, number, response.id) }
            .onFailure { log.error("Failed to post GitHub PR comment: owner={}, repo={}, number={}, error={}", inquiry.owner, inquiry.repo, number, it.message, it) }
    }

    override fun postPullRequestInlineComments(inquiry: GitDifferInquiry, number: Int, review: LlmInlineReviewResult, compareResult: GitCompareResult): Boolean {
        if (!isTokenConfigured()) {
            log.warn("Skip GitHub inline review — token is not configured (owner={}, repo={}, number={})", inquiry.owner, inquiry.repo, number)
            return false
        }
        if (inquiry.head.isBlank()) {
            log.warn("Skip GitHub inline review — missing head commit SHA (owner={}, repo={}, number={})", inquiry.owner, inquiry.repo, number)
            return false
        }
        val parsedDiffs = compareResult.parseDiffs()
        val filtered = review.comments.filter { it.body.isNotBlank() && parsedDiffs[it.file]?.lookup(it.line, it.side) != null }
        val dropped = review.comments.size - filtered.size
        if (dropped > 0) {
            log.info("GitHub inline review — dropped {} of {} LLM comments outside diff (number={})", dropped, review.comments.size, number)
        }
        if (filtered.isEmpty()) {
            log.warn("GitHub inline review — no valid inline comments after diff filtering (number={})", number)
            return false
        }
        val reviewComments = filtered.map { GithubReviewComment(path = it.file, line = it.line, side = it.side.name, body = it.body) }
        val body = review.summary.ifBlank { "AI incremental review" }
        val request = GithubReviewRequest(commitId = inquiry.head, body = body, event = "COMMENT", comments = reviewComments)
        val response = runCatching { githubConnector.createReview(inquiry.owner, inquiry.repo, number, request) }
            .getOrElse {
                log.error("Failed to post GitHub review: owner={}, repo={}, number={}, error={}", inquiry.owner, inquiry.repo, number, it.message, it)
                return false
            }
        if (!response.errorMessage.isNullOrBlank()) {
            log.warn("GitHub inline review rejected — {} (owner={}, repo={}, number={})", response.errorMessage, inquiry.owner, inquiry.repo, number)
            return false
        }
        log.info("Posted GitHub inline review: owner={}, repo={}, number={}, reviewId={}, comments={}", inquiry.owner, inquiry.repo, number, response.id, reviewComments.size)
        return true
    }

    private fun mergeFiles(files: List<GithubFile>): List<GithubFile> =
        files.groupBy { it.filename }
            .values
            .map { sameFileChanges ->
                val latest = sameFileChanges.last()
                latest.copy(
                    additions = sameFileChanges.sumOf { it.additions },
                    deletions = sameFileChanges.sumOf { it.deletions },
                    changes = sameFileChanges.sumOf { it.changes },
                    patch = sameFileChanges.mapNotNull { it.patch.takeIf(String::isNotBlank) }.joinToString("\n"),
                )
            }
}
