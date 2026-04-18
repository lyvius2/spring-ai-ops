package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_TOKEN
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_URL
import com.walter.spring.ai.ops.connector.GitlabConnector
import com.walter.spring.ai.ops.connector.dto.GitCompareResult
import com.walter.spring.ai.ops.connector.dto.GitDifferInquiry
import com.walter.spring.ai.ops.connector.dto.GitlabCompareResult
import com.walter.spring.ai.ops.util.CryptoProvider
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

    override val redisTokenKey: String = REDIS_KEY_GITLAB_TOKEN
    override val redisUrlKey: String = REDIS_KEY_GITLAB_URL

    fun setGitlabToken(token: String) = setToken(token)
    fun getGitlabToken(): String? = getToken()
    fun setGitlabUrl(url: String) = setUrl(url)
    fun getGitlabUrl(): String = getUrl()

    override fun executeInquiryDiffer(inquiry: GitDifferInquiry): GitCompareResult {
        return if (inquiry.base == EMPTY_SHA) {
            val commit = gitlabConnector.getCommit(inquiry.projectPath, inquiry.head)
            GitlabCompareResult(commits = listOf(commit), errorMessage = commit.errorMessage)
        } else {
            gitlabConnector.compare(inquiry.projectPath, inquiry.base, inquiry.head)
        }
    }
}

