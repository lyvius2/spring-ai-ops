package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.connector.dto.GitlabApiCommit
import com.walter.spring.ai.ops.connector.dto.GitlabCompareResult
import org.slf4j.LoggerFactory
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component

@Component
class GitlabConnectorFallbackFactory : FallbackFactory<GitlabConnector> {
    private val log = LoggerFactory.getLogger(GitlabConnectorFallbackFactory::class.java)

    override fun create(cause: Throwable): GitlabConnector {
        return object : GitlabConnector {
            override fun compare(projectPath: String, from: String, to: String): GitlabCompareResult {
                log.error("GitLab compare failed: projectPath={}, from={}, to={}, error={}", projectPath, from, to, cause.message, cause)
                return GitlabCompareResult(errorMessage = cause.message ?: "Failed to connect to GitLab API.")
            }
            override fun getCommit(projectPath: String, sha: String): GitlabApiCommit {
                log.error("GitLab getCommit failed: projectPath={}, sha={}, error={}", projectPath, sha, cause.message, cause)
                return GitlabApiCommit(errorMessage = cause.message ?: "Failed to connect to GitLab API.")
            }
        }
    }
}

