package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.GithubTokenRequest
import com.walter.spring.ai.ops.controller.dto.GithubTokenSaveResponse
import com.walter.spring.ai.ops.controller.dto.GithubTokenStatusResponse
import com.walter.spring.ai.ops.service.GithubService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/github")
class GithubConfigController(
    private val githubService: GithubService,
) {
    @GetMapping("/token/status")
    fun tokenStatus(): GithubTokenStatusResponse {
        return GithubTokenStatusResponse(githubService.isTokenConfigured())
    }

    @PostMapping("/token")
    fun saveToken(@RequestBody request: GithubTokenRequest): GithubTokenSaveResponse {
        if (request.token.isBlank()) {
            return GithubTokenSaveResponse.failure("Token must not be blank.")
        }
        githubService.setGithubToken(request.token)
        return GithubTokenSaveResponse.success()
    }
}
