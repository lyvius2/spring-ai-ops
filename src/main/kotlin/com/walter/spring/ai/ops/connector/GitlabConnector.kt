package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.config.GitlabConnectorConfig
import com.walter.spring.ai.ops.config.GitlabConnectorConfig.Companion.PLACEHOLDER_URL
import com.walter.spring.ai.ops.connector.dto.GitlabApiCommit
import com.walter.spring.ai.ops.connector.dto.GitlabCompareResult
import com.walter.spring.ai.ops.connector.dto.GitlabFile
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

@FeignClient(name = "gitlabConnector", url = PLACEHOLDER_URL, configuration = [GitlabConnectorConfig::class], fallbackFactory = GitlabConnectorFallbackFactory::class)
interface GitlabConnector {
    @GetMapping("/projects/{projectPath}/repository/compare")
    fun compare(@PathVariable projectPath: String, @RequestParam from: String, @RequestParam to: String): GitlabCompareResult

    @GetMapping("/projects/{projectPath}/repository/commits/{sha}")
    fun getCommit(@PathVariable projectPath: String, @PathVariable sha: String): GitlabApiCommit

    @GetMapping("/projects/{projectPath}/repository/commits/{sha}/diff")
    fun getCommitDiff(@PathVariable projectPath: String, @PathVariable sha: String): List<GitlabFile>
}
