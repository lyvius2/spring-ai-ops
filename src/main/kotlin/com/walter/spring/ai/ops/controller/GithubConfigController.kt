package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.controller.dto.GitRemoteConfigRequest
import com.walter.spring.ai.ops.controller.dto.GitRemoteConfigResponse
import com.walter.spring.ai.ops.controller.dto.GitRemoteStatusResponse
import com.walter.spring.ai.ops.controller.dto.GithubTokenRequest
import com.walter.spring.ai.ops.controller.dto.GithubTokenSaveResponse
import com.walter.spring.ai.ops.controller.dto.GithubTokenStatusResponse
import com.walter.spring.ai.ops.controller.dto.GithubUrlRequest
import com.walter.spring.ai.ops.controller.dto.GithubUrlSaveResponse
import com.walter.spring.ai.ops.controller.dto.GithubUrlStatusResponse
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "GitHub Configuration", description = "GitHub personal access token management for code review")
@RestController
@RequestMapping("/api/github")
class GithubConfigController(
    private val githubService: GithubService,
    private val gitlabService: GitlabService,
) {
    @Operation(summary = "Check whether a GitHub access token is configured")
    @GetMapping("/token/status")
    fun tokenStatus(): GithubTokenStatusResponse {
        return GithubTokenStatusResponse(githubService.isTokenConfigured())
    }

    @Operation(
        summary = "Save GitHub personal access token",
        description = "Persists the token in Redis. The token requires `repo` read scope to fetch commit diffs."
    )
    @PostMapping("/token")
    fun saveToken(@RequestBody request: GithubTokenRequest): GithubTokenSaveResponse {
        if (request.token.isBlank()) {
            return GithubTokenSaveResponse.failure("Token must not be blank.")
        }
        githubService.setGithubToken(request.token)
        return GithubTokenSaveResponse.success()
    }

    @Operation(summary = "Get GitHub base URL configuration status")
    @GetMapping("/url/status")
    fun urlStatus(): GithubUrlStatusResponse {
        return GithubUrlStatusResponse(githubService.isUrlConfigured(), githubService.getGithubUrl())
    }

    @Operation(
        summary = "Save GitHub base URL",
        description = "Persists the URL in Redis. Redis value takes priority over the property-configured URL."
    )
    @PostMapping("/url")
    fun saveUrl(@RequestBody request: GithubUrlRequest): GithubUrlSaveResponse {
        if (request.url.isBlank()) {
            return GithubUrlSaveResponse.failure("URL must not be blank.")
        }
        githubService.setGithubUrl(request.url)
        return GithubUrlSaveResponse.success()
    }

    @Operation(summary = "Save Git Remote provider, access token and base URL")
    @PostMapping("/config")
    fun saveConfig(@RequestBody request: GitRemoteConfigRequest): GitRemoteConfigResponse {
        if (request.token.isBlank()) {
            return GitRemoteConfigResponse.failure("Token must not be blank.")
        }
        val provider = runCatching { GitRemoteProvider.valueOf(request.provider) }.getOrNull()
            ?: return GitRemoteConfigResponse.failure("Unknown provider: ${request.provider}")

        githubService.setGitRemoteProvider(provider)
        when (provider) {
            GitRemoteProvider.GITHUB -> {
                githubService.setGithubToken(request.token)
                if (request.url.isNotBlank()) githubService.setGithubUrl(request.url)
            }
            GitRemoteProvider.GITLAB -> {
                gitlabService.setGitlabToken(request.token)
                if (request.url.isNotBlank()) gitlabService.setGitlabUrl(request.url)
            }
        }
        return GitRemoteConfigResponse.success()
    }

    @Operation(summary = "Get Git Remote configuration status for all providers")
    @GetMapping("/config/status")
    fun configStatus(): GitRemoteStatusResponse {
        return GitRemoteStatusResponse(
            githubTokenConfigured = githubService.isTokenConfigured(),
            githubPropertyConfigured = githubService.isPropertyConfigured(),
            gitlabTokenConfigured = gitlabService.isTokenConfigured(),
            gitlabPropertyConfigured = gitlabService.isPropertyConfigured(),
            currentProvider = githubService.getGitRemoteProvider()?.name,
            githubUrl = githubService.getGithubUrl(),
            gitlabUrl = gitlabService.getGitlabUrl(),
        )
    }
}
