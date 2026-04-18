package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.controller.dto.GitRemoteConfigRequest
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.GitRemoteService

@Facade
class GitRemoteFacade(
    private val githubService: GithubService,
    private val gitlabService: GitlabService,
) {
    fun setConfig(request: GitRemoteConfigRequest, provider: GitRemoteProvider) {
        githubService.setGitRemoteProvider(provider)
        val service = resolveService(provider)
        if (request.token.isNotBlank()) {
            service.setToken(request.token)
        }
        if (request.url.isNotBlank()) {
            service.setUrl(request.url)
        }
    }

    fun getConfig(): Map<String, Any?> {
        return mapOf(
            "currentProvider" to githubService.getGitRemoteProvider()?.name,
            "githubTokenConfigured" to githubService.isTokenConfigured(),
            "githubPropertyConfigured" to githubService.isPropertyConfigured(),
            "gitlabTokenConfigured" to gitlabService.isTokenConfigured(),
            "gitlabPropertyConfigured" to gitlabService.isPropertyConfigured(),
            "githubUrl" to githubService.getUrl(),
            "gitlabUrl" to gitlabService.getUrl(),
        )
    }

    private fun resolveService(provider: GitRemoteProvider): GitRemoteService = when (provider) {
        GitRemoteProvider.GITHUB -> githubService
        GitRemoteProvider.GITLAB -> gitlabService
    }
}