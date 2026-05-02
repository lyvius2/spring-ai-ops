package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_TOKEN
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_URL
import com.walter.spring.ai.ops.connector.GithubConnector
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubFile
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
