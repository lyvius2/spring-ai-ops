package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.GithubTokenRequest
import com.walter.spring.ai.ops.controller.dto.GithubTokenSaveResponse
import com.walter.spring.ai.ops.controller.dto.GithubTokenStatusResponse
import com.walter.spring.ai.ops.controller.dto.GithubUrlRequest
import com.walter.spring.ai.ops.controller.dto.GithubUrlSaveResponse
import com.walter.spring.ai.ops.controller.dto.GithubUrlStatusResponse
import com.walter.spring.ai.ops.service.GithubService
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
}
