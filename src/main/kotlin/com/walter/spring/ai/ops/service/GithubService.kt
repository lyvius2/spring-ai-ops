package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.GitHubConstants.Companion.EMPTY_SHA
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_COMMIT_PREFIX
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_URL
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GIT_REMOTE_TOKEN
import com.walter.spring.ai.ops.connector.GithubConnector
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubDifferInquiry
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.util.CryptoProvider
import com.walter.spring.ai.ops.util.zSetPushWithTtl
import com.walter.spring.ai.ops.util.zSetRangeAllDesc
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class GithubService(
    private val redisTemplate: StringRedisTemplate,
    private val githubConnector: GithubConnector,
    private val objectMapper: ObjectMapper,
    private val cryptoProvider: CryptoProvider,
    @Value("\${analysis.data-retention-hours:120}") private val retentionHours: Long,
    @Value("\${analysis.maximum-view-count:5}") private val maximumViewCount: Long,
    @Value("\${github.access-token:}") private val configuredToken: String,
    @Value("\${github.url:https://api.github.com}") private val githubUrlFromConfig: String,
) {
    private val log = LoggerFactory.getLogger(GithubService::class.java)

    fun setGithubToken(token: String) {
        redisTemplate.opsForValue().set(REDIS_KEY_GIT_REMOTE_TOKEN, cryptoProvider.encrypt(token))
    }

    fun getGithubToken(): String? {
        val redisToken = redisTemplate.opsForValue().get(REDIS_KEY_GIT_REMOTE_TOKEN)
            ?.let { cryptoProvider.decrypt(it) }
        if (!redisToken.isNullOrBlank()) return redisToken
        if (configuredToken.isNotBlank()) return configuredToken
        return null
    }

    fun isTokenConfigured(): Boolean {
        return !getGithubToken().isNullOrBlank()
    }

    fun setGithubUrl(url: String) {
        redisTemplate.opsForValue().set(REDIS_KEY_GITHUB_URL, url)
    }

    fun getGithubUrl(): String {
        return redisTemplate.opsForValue().get(REDIS_KEY_GITHUB_URL)?.takeIf { it.isNotBlank() }
            ?: githubUrlFromConfig
    }

    fun isUrlConfigured(): Boolean {
        return getGithubUrl().isNotBlank()
    }

    fun executeInquiryDiffer(inquiry: GithubDifferInquiry): GithubCompareResult {
        return if (inquiry.base == EMPTY_SHA) {
            githubConnector.getCommit(inquiry.owner, inquiry.repo, inquiry.head)
        } else {
            val compareResult = githubConnector.compare(inquiry.owner, inquiry.repo, "${inquiry.base}...${inquiry.head}")
            if (compareResult.files.isEmpty() && compareResult.errorMessage.isBlank()) {
                log.warn("GitHub compare returned empty files (250-commit limit?), falling back to getCommit: head={}", inquiry.head)
                githubConnector.getCommit(inquiry.owner, inquiry.repo, inquiry.head)
            } else {
                compareResult
            }
        }
    }

    fun saveCodeReviewRecord(record: CodeReviewRecord) {
        val key = "${REDIS_KEY_COMMIT_PREFIX}${record.application}"
        redisTemplate.zSetPushWithTtl(key, objectMapper.writeValueAsString(record), retentionHours)
    }

    fun getCodeReviewRecords(application: String): List<CodeReviewRecord> {
        val key = "${REDIS_KEY_COMMIT_PREFIX}${application}"
        return redisTemplate.zSetRangeAllDesc(key, maximumViewCount)
            .mapNotNull { runCatching { objectMapper.readValue(it, CodeReviewRecord::class.java) }.getOrNull() }
    }
}
