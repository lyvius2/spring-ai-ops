package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.connector.GithubConnector
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubDifferInquiry
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class GithubService(
    private val redisTemplate: StringRedisTemplate,
    private val githubConnector: GithubConnector,
    @Value("\${github.access-token:}") private val configuredToken: String,
) {
    companion object {
        const val GITHUB_TOKEN_KEY = "githubToken"
    }

    fun setGithubToken(token: String) {
        redisTemplate.opsForValue().set(GITHUB_TOKEN_KEY, token)
    }

    fun getGithubToken(): String? {
        val redisToken = redisTemplate.opsForValue().get(GITHUB_TOKEN_KEY)
        if (!redisToken.isNullOrBlank()) {
            return redisToken
        }
        if (configuredToken.isNotBlank()) {
            return configuredToken
        }
        return null
    }

    fun isTokenConfigured(): Boolean = !getGithubToken().isNullOrBlank()

    fun executeInquiryDiffer(inquiry: GithubDifferInquiry): GithubCompareResult {
        return githubConnector.compare(inquiry.owner, inquiry.repo, "${inquiry.base}...${inquiry.head}")
    }
}
