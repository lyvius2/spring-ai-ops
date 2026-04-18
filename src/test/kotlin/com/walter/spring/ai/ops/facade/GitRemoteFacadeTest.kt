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
    @DisplayName("GITHUB provider로 설정 시 githubService에 token이 저장된다")
    fun givenGithubProvider_whenSetConfig_thenSavesTokenToGithubService() {
        // given
        val request = GitRemoteConfigRequest(provider = "GITHUB", token = "ghp_test", url = "")

        // when
        facade.setConfig(request, GitRemoteProvider.GITHUB)

        // then
        verify(githubService).setToken("ghp_test")
        verify(gitlabService, never()).setToken(any())
    }

    @Test
    @DisplayName("GITLAB provider로 설정 시 gitlabService에 token이 저장된다")
    fun givenGitlabProvider_whenSetConfig_thenSavesTokenToGitlabService() {
        // given
        val request = GitRemoteConfigRequest(provider = "GITLAB", token = "glpat-test", url = "")

        // when
        facade.setConfig(request, GitRemoteProvider.GITLAB)

        // then
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
    @DisplayName("token이 비어있으면 setToken을 호출하지 않는다 (기존 token 유지)")
    fun givenBlankToken_whenSetConfig_thenDoesNotCallSetToken() {
        // given
        val request = GitRemoteConfigRequest(provider = "GITHUB", token = "", url = "")

        // when
        facade.setConfig(request, GitRemoteProvider.GITHUB)

        // then
        verify(githubService, never()).setToken(any())
        verify(gitlabService, never()).setToken(any())
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
    @DisplayName("getConfig는 양쪽 서비스의 토큰·URL 상태를 모두 반환한다")
    fun givenBothServicesConfigured_whenGetConfig_thenReturnsAllStatus() {
        // given
        `when`(githubService.isTokenConfigured()).thenReturn(true)
        `when`(githubService.isPropertyConfigured()).thenReturn(false)
        `when`(githubService.getUrl()).thenReturn("https://api.github.com")
        `when`(gitlabService.isTokenConfigured()).thenReturn(false)
        `when`(gitlabService.isPropertyConfigured()).thenReturn(false)
        `when`(gitlabService.getUrl()).thenReturn("https://gitlab.com/api/v4")

        // when
        val config = facade.getConfig()

        // then
        assertThat(config["githubTokenConfigured"]).isEqualTo(true)
        assertThat(config["githubPropertyConfigured"]).isEqualTo(false)
        assertThat(config["gitlabTokenConfigured"]).isEqualTo(false)
        assertThat(config["gitlabPropertyConfigured"]).isEqualTo(false)
        assertThat(config["githubUrl"]).isEqualTo("https://api.github.com")
        assertThat(config["gitlabUrl"]).isEqualTo("https://gitlab.com/api/v4")
        assertThat(config).doesNotContainKey("currentProvider")
    }

    @Test
    @DisplayName("GitHub과 GitLab 모두 토큰이 등록된 경우 두 서비스 모두 true를 반환한다")
    fun givenBothTokensConfigured_whenGetConfig_thenBothTokenConfiguredAreTrue() {
        // given
        `when`(githubService.isTokenConfigured()).thenReturn(true)
        `when`(githubService.isPropertyConfigured()).thenReturn(false)
        `when`(githubService.getUrl()).thenReturn("https://api.github.com")
        `when`(gitlabService.isTokenConfigured()).thenReturn(true)
        `when`(gitlabService.isPropertyConfigured()).thenReturn(false)
        `when`(gitlabService.getUrl()).thenReturn("https://gitlab.com/api/v4")

        // when
        val config = facade.getConfig()

        // then
        assertThat(config["githubTokenConfigured"]).isEqualTo(true)
        assertThat(config["gitlabTokenConfigured"]).isEqualTo(true)
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private fun <T> any(): T = org.mockito.ArgumentMatchers.any()
}

