package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_COMMIT_PREFIX
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.util.CryptoProvider
import com.walter.spring.ai.ops.util.zSetPushWithTtl
import com.walter.spring.ai.ops.util.zSetRangeAllDesc
import org.springframework.data.redis.core.StringRedisTemplate

abstract class GitRemoteService(
    protected val redisTemplate: StringRedisTemplate,
    protected val objectMapper: ObjectMapper,
    protected val cryptoProvider: CryptoProvider,
    protected val retentionHours: Long,
    protected val maximumViewCount: Long,
) {
    companion object {
        const val EMPTY_SHA = "0000000000000000000000000000000000000000"
    }

    protected abstract val redisUrlKey: String
    protected abstract val redisTokenKey: String
    protected abstract val configuredUrl: String
    protected abstract val configuredToken: String

    fun isPropertyConfigured(): Boolean = configuredToken.isNotBlank()

    fun setToken(token: String) {
        redisTemplate.opsForValue().set(redisTokenKey, cryptoProvider.encrypt(token))
    }

    fun getToken(): String? {
        val redisToken = redisTemplate.opsForValue().get(redisTokenKey)
            ?.let { cryptoProvider.decrypt(it) }
        if (!redisToken.isNullOrBlank()) return redisToken
        if (configuredToken.isNotBlank()) return configuredToken
        return null
    }

    fun isTokenConfigured(): Boolean = !getToken().isNullOrBlank()

    fun setUrl(url: String) {
        redisTemplate.opsForValue().set(redisUrlKey, url)
    }

    fun getUrl(): String =
        redisTemplate.opsForValue().get(redisUrlKey)?.takeIf { it.isNotBlank() }
            ?: configuredUrl

    fun isUrlConfigured(): Boolean = getUrl().isNotBlank()

    fun saveCodeReviewRecord(record: CodeReviewRecord) {
        val key = "$REDIS_KEY_COMMIT_PREFIX${record.application}"
        redisTemplate.zSetPushWithTtl(key, objectMapper.writeValueAsString(record), retentionHours)
    }

    fun getCodeReviewRecords(application: String): List<CodeReviewRecord> {
        val key = "$REDIS_KEY_COMMIT_PREFIX$application"
        return redisTemplate.zSetRangeAllDesc(key)
            .mapNotNull { runCatching { objectMapper.readValue(it, CodeReviewRecord::class.java) }.getOrNull() }
            .let { if (maximumViewCount > 0) it.take(maximumViewCount.toInt()) else it }
    }

    abstract fun executeInquiryDiffer(inquiry: GitDifferInquiry): GitCompareResult
}
