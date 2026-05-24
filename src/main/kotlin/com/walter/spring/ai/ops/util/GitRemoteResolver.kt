package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.service.GitRemoteService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import org.springframework.stereotype.Component

@Component
class GitRemoteResolver(
    private val githubService: GithubService,
    private val gitlabService: GitlabService,
) {
    fun getToken(gitUrl: String): String? {
        val lowerUrl = gitUrl.lowercase()
        return when {
            lowerUrl.contains("github") -> githubService.getToken()
            lowerUrl.contains("gitlab") -> gitlabService.getToken()
            else -> ""
        }
    }

    fun detectProviderService(source: GitRemoteProvider): GitRemoteService {
        return when (source) {
            GitRemoteProvider.GITHUB -> githubService
            GitRemoteProvider.GITLAB -> gitlabService
        }
    }
}