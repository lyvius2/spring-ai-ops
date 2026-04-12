package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import org.slf4j.LoggerFactory
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component

@Component
class GithubConnectorFallbackFactory : FallbackFactory<GithubConnector> {
    private val log = LoggerFactory.getLogger(GithubConnectorFallbackFactory::class.java)

    override fun create(cause: Throwable): GithubConnector {
        return object : GithubConnector {
            override fun compare(owner: String, repo: String, basehead: String): GithubCompareResult {
                log.error("GitHub compare failed: owner={}, repo={}, basehead={}, error={}", owner, repo, basehead, cause.message, cause)
                return GithubCompareResult(errorMessage = cause.message ?: "Failed to connect to GitHub API.")
            }

            override fun getCommit(owner: String, repo: String, sha: String): GithubCompareResult {
                log.error("GitHub getCommit failed: owner={}, repo={}, sha={}, error={}", owner, repo, sha, cause.message, cause)
                return GithubCompareResult(errorMessage = cause.message ?: "Failed to connect to GitHub API.")
            }
        }
    }
}
