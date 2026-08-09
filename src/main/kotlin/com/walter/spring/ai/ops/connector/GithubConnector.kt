package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.config.GithubConnectorConfig
import com.walter.spring.ai.ops.config.GithubConnectorConfig.Companion.PLACEHOLDER_URL
import com.walter.spring.ai.ops.connector.dto.GitCommentRequest
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import com.walter.spring.ai.ops.connector.dto.GithubIssueCommentResponse
import com.walter.spring.ai.ops.connector.dto.GithubReviewRequest
import com.walter.spring.ai.ops.connector.dto.GithubReviewResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

@FeignClient(name = "githubConnector", url = PLACEHOLDER_URL, configuration = [GithubConnectorConfig::class], fallbackFactory = GithubConnectorFallbackFactory::class,)
interface GithubConnector {
    @GetMapping("/repos/{owner}/{repo}/compare/{basehead}")
    fun compare(@PathVariable owner: String, @PathVariable repo: String, @PathVariable basehead: String): GithubCompareResult

    @GetMapping("/repos/{owner}/{repo}/commits/{sha}")
    fun getCommit(@PathVariable owner: String, @PathVariable repo: String, @PathVariable sha: String): GithubCompareResult

    @PostMapping("/repos/{owner}/{repo}/issues/{number}/comments")
    fun createIssueComment(@PathVariable owner: String, @PathVariable repo: String, @PathVariable number: Int, @RequestBody request: GitCommentRequest): GithubIssueCommentResponse

    @PostMapping("/repos/{owner}/{repo}/pulls/{number}/reviews")
    fun createReview(@PathVariable owner: String, @PathVariable repo: String, @PathVariable number: Int, @RequestBody request: GithubReviewRequest): GithubReviewResponse
}
