package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.connector.dto.GitCommentRequest
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubIssueCommentResponse
import com.walter.spring.ai.ops.connector.dto.GithubReviewRequest
import com.walter.spring.ai.ops.connector.dto.GithubReviewResponse
import com.walter.spring.ai.ops.connector.dto.GitBranch
import com.walter.spring.ai.ops.connector.dto.GithubApiCommit
import org.slf4j.LoggerFactory
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component

@Component
class GithubConnectorFallbackFactory : FallbackFactory<GithubConnector> {
    private val log = LoggerFactory.getLogger(GithubConnectorFallbackFactory::class.java)

    override fun create(cause: Throwable): GithubConnector {
        return object : GithubConnector {
            override fun getBranches(owner: String, repo: String, perPage: Int): List<GitBranch> {
                log.error("GitHub getBranches failed: owner={}, repo={}, error={}", owner, repo, cause.message, cause)
                return emptyList()
            }

            override fun getCommits(owner: String, repo: String, sha: String, perPage: Int): List<GithubApiCommit> {
                log.error("GitHub getCommits failed: owner={}, repo={}, branch={}, error={}", owner, repo, sha, cause.message, cause)
                return emptyList()
            }

            override fun compare(owner: String, repo: String, basehead: String): GithubCompareResult {
                log.error("GitHub compare failed: owner={}, repo={}, basehead={}, error={}", owner, repo, basehead, cause.message, cause)
                return GithubCompareResult(errorMessage = cause.message ?: "Failed to connect to GitHub API.")
            }

            override fun getCommit(owner: String, repo: String, sha: String): GithubCompareResult {
                log.error("GitHub getCommit failed: owner={}, repo={}, sha={}, error={}", owner, repo, sha, cause.message, cause)
                return GithubCompareResult(errorMessage = cause.message ?: "Failed to connect to GitHub API.")
            }

            override fun createIssueComment(owner: String, repo: String, number: Int, request: GitCommentRequest): GithubIssueCommentResponse {
                log.error("GitHub createIssueComment failed: owner={}, repo={}, number={}, error={}", owner, repo, number, cause.message, cause)
                return GithubIssueCommentResponse(errorMessage = cause.message ?: "Failed to connect to GitHub API.")
            }

            override fun createReview(owner: String, repo: String, number: Int, request: GithubReviewRequest): GithubReviewResponse {
                log.error("GitHub createReview failed: owner={}, repo={}, number={}, error={}", owner, repo, number, cause.message, cause)
                return GithubReviewResponse(errorMessage = cause.message ?: "Failed to connect to GitHub API.")
            }
        }
    }
}
