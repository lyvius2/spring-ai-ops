package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.controller.dto.GitRemoteConfigRequest
import com.walter.spring.ai.ops.controller.dto.GitRemoteConfigResponse
import com.walter.spring.ai.ops.controller.dto.GitRemoteStatusResponse
import com.walter.spring.ai.ops.facade.GitRemoteFacade
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
    private val gitRemoteFacade: GitRemoteFacade
) {
    @Operation(summary = "Save Git Remote provider, access token and base URL")
    @PostMapping("/config")
    fun saveConfig(@RequestBody request: GitRemoteConfigRequest): GitRemoteConfigResponse {
        if (request.token.isBlank()) {
            return GitRemoteConfigResponse.failure("Token must not be blank.")
        }
        val provider = runCatching { GitRemoteProvider.valueOf(request.provider) }.getOrNull()
            ?: return GitRemoteConfigResponse.failure("Unknown provider: ${request.provider}")
        gitRemoteFacade.setConfig(request, provider)
        return GitRemoteConfigResponse.success()
    }

    @Operation(summary = "Get Git Remote configuration status for all providers")
    @GetMapping("/config/status")
    fun configStatus(): GitRemoteStatusResponse {
        return GitRemoteStatusResponse.of(gitRemoteFacade.getConfig())
    }
}
