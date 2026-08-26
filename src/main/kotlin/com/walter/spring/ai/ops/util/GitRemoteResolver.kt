package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.service.GitRemoteService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import org.springframework.stereotype.Component
import com.walter.spring.ai.ops.service.dto.GitRepositoryCoordinates
import java.net.URI

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

    fun detectProviderServiceByUrl(gitUrl: String): GitRemoteService {
        val host = runCatching { URI(gitUrl).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host.contains("github") -> githubService
            host.contains("gitlab") -> gitlabService
            else -> throw IllegalArgumentException("Only GitHub and GitLab repository URLs are supported.")
        }
    }

    fun parseCoordinates(gitUrl: String): GitRepositoryCoordinates {
        val path = runCatching { URI(gitUrl).path }.getOrNull()
            ?.trim('/')
            ?.removeSuffix(".git")
            ?: throw IllegalArgumentException("Invalid Git repository URL.")
        val segments = path.split('/').filter { it.isNotBlank() }
        require(segments.size >= 2) { "Git repository URL must include an owner and repository." }
        return GitRepositoryCoordinates(segments.dropLast(1).joinToString("/"), segments.last())
    }
}
