package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_COMMIT_PREFIX
import com.walter.spring.ai.ops.connector.cache.CacheStorePort
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.controller.dto.GithubPullRequestRequest
import com.walter.spring.ai.ops.record.CodeReviewRecord
import com.walter.spring.ai.ops.service.dto.LlmInlineReviewResult
import com.walter.spring.ai.ops.util.CryptoProvider

abstract class GitRemoteService(
    protected val cacheStorePort: CacheStorePort,
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
        cacheStorePort.set(redisTokenKey, cryptoProvider.encrypt(token))
    }

    fun getToken(): String? {
        val redisToken = cacheStorePort.get(redisTokenKey)
            ?.let { cryptoProvider.decrypt(it) }
        if (!redisToken.isNullOrBlank()) return redisToken
        if (configuredToken.isNotBlank()) return configuredToken
        return null
    }

    fun isTokenConfigured(): Boolean = !getToken().isNullOrBlank()

    fun setUrl(url: String) {
        cacheStorePort.set(redisUrlKey, url)
    }

    fun getUrl(): String =
        cacheStorePort.get(redisUrlKey)?.takeIf { it.isNotBlank() }
            ?: configuredUrl

    fun isUrlConfigured(): Boolean = getUrl().isNotBlank()

    fun saveCodeReviewRecord(record: CodeReviewRecord) {
        val key = "$REDIS_KEY_COMMIT_PREFIX${record.application}"
        cacheStorePort.addToTimeOrderedSet(key, objectMapper.writeValueAsString(record), retentionHours)
    }

    fun getCodeReviewRecords(application: String): List<CodeReviewRecord> {
        val key = "$REDIS_KEY_COMMIT_PREFIX$application"
        return cacheStorePort.getTimeOrderedSetDescending(key)
            .mapNotNull { runCatching { objectMapper.readValue(it, CodeReviewRecord::class.java) }.getOrNull() }
            .let { if (maximumViewCount > 0) it.take(maximumViewCount.toInt()) else it }
    }

    fun formatPullRequestComment(reviewMarkdown: String): String = buildString {
        appendLine("## AI Code Review")
        appendLine()
        appendLine(reviewMarkdown.trim())
        appendLine()
        appendLine("---")
        appendLine("_Automated review by Spring AI Ops._")
    }

    abstract fun executeInquiryDiffer(inquiry: GitDifferInquiry): GitCompareResult

    abstract fun postPullRequestComment(inquiry: GitDifferInquiry, number: Int, body: String)

    abstract fun postPullRequestInlineComments(inquiry: GitDifferInquiry, number: Int, review: LlmInlineReviewResult, compareResult: GitCompareResult): Boolean

    open fun resolveMissingPullRequestRefs(request: GithubPullRequestRequest): GithubPullRequestRequest = request
}
