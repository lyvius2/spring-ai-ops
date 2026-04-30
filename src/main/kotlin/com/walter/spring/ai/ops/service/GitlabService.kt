package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_TOKEN
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_URL
import com.walter.spring.ai.ops.connector.GitlabConnector
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GitlabCompareResult
import com.walter.spring.ai.ops.connector.dto.GitlabFile
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
