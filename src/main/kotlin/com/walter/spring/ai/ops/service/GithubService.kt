package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_COMMIT_PREFIX
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITHUB_URL
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GIT_REMOTE_TOKEN
import com.walter.spring.ai.ops.connector.GithubConnector
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubDifferInquiry
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.util.listPushWithTtl
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class GithubService(
    private val redisTemplate: StringRedisTemplate,
    private val githubConnector: GithubConnector,
    private val objectMapper: ObjectMapper,
    @Value("\${analysis.data-retention-hours:120}") private val retentionHours: Long,
    @Value("\${analysis.maximum-view-count:5}") private val maximumViewCount: Long,
    @Value("\${github.access-token:}") private val configuredToken: String,
    @Value("\${github.url:https://api.github.com}") private val githubUrlFromConfig: String,
) {
    companion object {
        const val EMPTY_SHA = "0000000000000000000000000000000000000000"
    }

    fun setGithubToken(token: String) {
        redisTemplate.opsForValue().set(REDIS_KEY_GIT_REMOTE_TOKEN, token)
    }

    fun getGithubToken(): String? {
        val redisToken = redisTemplate.opsForValue().get(REDIS_KEY_GIT_REMOTE_TOKEN)
        if (!redisToken.isNullOrBlank()) {
            return redisToken
        }
        if (configuredToken.isNotBlank()) {
            return configuredToken
        }
        return null
    }

    fun isTokenConfigured(): Boolean = !getGithubToken().isNullOrBlank()

    fun setGithubUrl(url: String) {
        redisTemplate.opsForValue().set(REDIS_KEY_GITHUB_URL, url)
    }

    fun getGithubUrl(): String =
        redisTemplate.opsForValue().get(REDIS_KEY_GITHUB_URL)?.takeIf { it.isNotBlank() }
            ?: githubUrlFromConfig

    fun isUrlConfigured(): Boolean = getGithubUrl().isNotBlank()

    fun executeInquiryDiffer(inquiry: GithubDifferInquiry): GithubCompareResult {
        return if (inquiry.base == EMPTY_SHA) {
            githubConnector.getCommit(inquiry.owner, inquiry.repo, inquiry.head)
        } else {
            githubConnector.compare(inquiry.owner, inquiry.repo, "${inquiry.base}...${inquiry.head}")
        }
    }

    fun saveCodeReviewRecord(record: CodeReviewRecord) {
        val key = "${REDIS_KEY_COMMIT_PREFIX}${record.application}"
        redisTemplate.listPushWithTtl(key, objectMapper.writeValueAsString(record), retentionHours)
    }

    fun getCodeReviewRecords(application: String): List<CodeReviewRecord> {
        val key = "${REDIS_KEY_COMMIT_PREFIX}${application}"
        return (redisTemplate.opsForList().range(key, 0, -1) ?: emptyList())
            .mapNotNull { runCatching { objectMapper.readValue(it.substringBeforeLast("::"), CodeReviewRecord::class.java) }.getOrNull() }
            .sortedByDescending { it.pushedAt }
            .let { if (maximumViewCount > 0) it.take(maximumViewCount.toInt()) else it }
    }
}
