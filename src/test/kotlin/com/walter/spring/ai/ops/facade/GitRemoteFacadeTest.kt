package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.controller.dto.GitRemoteConfigRequest
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

class GitRemoteFacadeTest {

    @Mock private lateinit var githubService: GithubService
    @Mock private lateinit var gitlabService: GitlabService

    private lateinit var facade: GitRemoteFacade

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        facade = GitRemoteFacade(githubService, gitlabService)
    }

    // ── setConfig ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GITHUB provider로 설정 시 githubService에 provider·token이 저장된다")
    fun givenGithubProvider_whenSetConfig_thenSavesProviderAndTokenToGithubService() {
        // given
        val request = GitRemoteConfigRequest(provider = "GITHUB", token = "ghp_test", url = "")

        // when
        facade.setConfig(request, GitRemoteProvider.GITHUB)

        // then
        verify(githubService).setGitRemoteProvider(GitRemoteProvider.GITHUB)
        verify(githubService).setToken("ghp_test")
        verify(gitlabService, never()).setToken(any())
    }

    @Test
    @DisplayName("GITLAB provider로 설정 시 gitlabService에 provider·token이 저장된다")
    fun givenGitlabProvider_whenSetConfig_thenSavesProviderAndTokenToGitlabService() {
        // given
        val request = GitRemoteConfigRequest(provider = "GITLAB", token = "glpat-test", url = "")

        // when
        facade.setConfig(request, GitRemoteProvider.GITLAB)

        // then
        verify(githubService).setGitRemoteProvider(GitRemoteProvider.GITLAB)
        verify(gitlabService).setToken("glpat-test")
        verify(githubService, never()).setToken(any())
    }

    @Test
    @DisplayName("url이 비어있지 않으면 해당 provider service에 url도 저장된다")
    fun givenNonBlankUrl_whenSetConfig_thenSavesUrlToService() {
        // given
        val request = GitRemoteConfigRequest(provider = "GITHUB", token = "ghp_test", url = "https://github.example.com")

        // when
        facade.setConfig(request, GitRemoteProvider.GITHUB)

        // then
        verify(githubService).setUrl("https://github.example.com")
    }

    @Test
    @DisplayName("url이 비어있으면 setUrl을 호출하지 않는다")
    fun givenBlankUrl_whenSetConfig_thenDoesNotCallSetUrl() {
        // given
        val request = GitRemoteConfigRequest(provider = "GITHUB", token = "ghp_test", url = "")

        // when
        facade.setConfig(request, GitRemoteProvider.GITHUB)

        // then
        verify(githubService, never()).setUrl(any())
        verify(gitlabService, never()).setUrl(any())
    }

    @Test
    @DisplayName("GITLAB provider + url 있으면 gitlabService에 url이 저장된다")
    fun givenGitlabProviderWithUrl_whenSetConfig_thenSavesUrlToGitlabService() {
        // given
        val request = GitRemoteConfigRequest(provider = "GITLAB", token = "glpat-test", url = "https://gitlab.example.com/api/v4")

        // when
        facade.setConfig(request, GitRemoteProvider.GITLAB)

        // then
        verify(gitlabService).setUrl("https://gitlab.example.com/api/v4")
        verify(githubService, never()).setUrl(any())
    }

    // ── getConfig ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getConfig는 현재 provider 및 양쪽 서비스의 상태를 모두 반환한다")
    fun givenBothServicesConfigured_whenGetConfig_thenReturnsAllStatus() {
        // given
        `when`(githubService.getGitRemoteProvider()).thenReturn(GitRemoteProvider.GITHUB)
        `when`(githubService.isTokenConfigured()).thenReturn(true)
        `when`(githubService.isPropertyConfigured()).thenReturn(false)
        `when`(githubService.getUrl()).thenReturn("https://api.github.com")
        `when`(gitlabService.isTokenConfigured()).thenReturn(false)
        `when`(gitlabService.isPropertyConfigured()).thenReturn(false)
        `when`(gitlabService.getUrl()).thenReturn("https://gitlab.com/api/v4")

        // when
        val config = facade.getConfig()

        // then
        assertThat(config["currentProvider"]).isEqualTo("GITHUB")
        assertThat(config["githubTokenConfigured"]).isEqualTo(true)
        assertThat(config["githubPropertyConfigured"]).isEqualTo(false)
        assertThat(config["gitlabTokenConfigured"]).isEqualTo(false)
        assertThat(config["gitlabPropertyConfigured"]).isEqualTo(false)
        assertThat(config["githubUrl"]).isEqualTo("https://api.github.com")
        assertThat(config["gitlabUrl"]).isEqualTo("https://gitlab.com/api/v4")
    }

    @Test
    @DisplayName("provider가 설정되지 않은 경우 currentProvider는 null이다")
    fun givenNoProviderSet_whenGetConfig_thenCurrentProviderIsNull() {
        // given
        `when`(githubService.getGitRemoteProvider()).thenReturn(null)
        `when`(githubService.isTokenConfigured()).thenReturn(false)
        `when`(githubService.isPropertyConfigured()).thenReturn(false)
        `when`(githubService.getUrl()).thenReturn("")
        `when`(gitlabService.isTokenConfigured()).thenReturn(false)
        `when`(gitlabService.isPropertyConfigured()).thenReturn(false)
        `when`(gitlabService.getUrl()).thenReturn("")

        // when
        val config = facade.getConfig()

        // then
        assertThat(config["currentProvider"]).isNull()
    }

    @Test
    @DisplayName("GITLAB provider로 설정된 경우 currentProvider는 GITLAB이다")
    fun givenGitlabProviderSet_whenGetConfig_thenCurrentProviderIsGitlab() {
        // given
        `when`(githubService.getGitRemoteProvider()).thenReturn(GitRemoteProvider.GITLAB)
        `when`(githubService.isTokenConfigured()).thenReturn(false)
        `when`(githubService.isPropertyConfigured()).thenReturn(false)
        `when`(githubService.getUrl()).thenReturn("https://api.github.com")
        `when`(gitlabService.isTokenConfigured()).thenReturn(true)
        `when`(gitlabService.isPropertyConfigured()).thenReturn(false)
        `when`(gitlabService.getUrl()).thenReturn("https://gitlab.com/api/v4")

        // when
        val config = facade.getConfig()

        // then
        assertThat(config["currentProvider"]).isEqualTo("GITLAB")
        assertThat(config["gitlabTokenConfigured"]).isEqualTo(true)
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private fun <T> any(): T = org.mockito.ArgumentMatchers.any()
}

