package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.config.GithubConnectorConfig
import com.walter.spring.ai.ops.connector.dto.GithubCompareResult
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient(name = "githubConnector", url = "\${github.url}", configuration = [GithubConnectorConfig::class], fallbackFactory = GithubConnectorFallbackFactory::class,)
interface GithubConnector {
    @GetMapping("/repos/{owner}/{repo}/compare/{basehead}")
    fun compare(@PathVariable owner: String, @PathVariable repo: String, @PathVariable basehead: String): GithubCompareResult

    @GetMapping("/repos/{owner}/{repo}/commits/{sha}")
    fun getCommit(@PathVariable owner: String, @PathVariable repo: String, @PathVariable sha: String): GithubCompareResult
}
