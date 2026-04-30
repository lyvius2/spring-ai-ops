package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.AlertMessageType
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.service.RepositoryService
import com.walter.spring.ai.ops.service.dto.AlertMessage
import com.walter.spring.ai.ops.service.dto.AppGitConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.nio.file.Path
import java.util.concurrent.Executor

@Suppress("UNCHECKED_CAST")
private fun <T> anyObject(): T = Mockito.any() as T

@ExtendWith(MockitoExtension::class)
class ApplicationFacadeTest {

    @Mock private lateinit var applicationService: ApplicationService
    @Mock private lateinit var repositoryService: RepositoryService
    @Mock private lateinit var githubService: GithubService
    @Mock private lateinit var gitlabService: GitlabService
    @Mock private lateinit var messageService: MessageService

    private lateinit var applicationFacade: ApplicationFacade

    private val inlineExecutor = Executor { it.run() }

    @BeforeEach
    fun setUp() {
        applicationFacade = ApplicationFacade(
            applicationService = applicationService,
            repositoryService = repositoryService,
            githubService = githubService,
            gitlabService = gitlabService,
            messageService = messageService,
            executor = inlineExecutor,
        )
    }

    @Test
    @DisplayName("앱 목록 조회는 ApplicationService에 위임한다")
    fun givenAppsExist_whenGetApps_thenDelegatesToApplicationService() {
        // given
        `when`(applicationService.getApps()).thenReturn(listOf("app1", "app2"))

        // when
        val result = applicationFacade.getApps()

        // then
        assertThat(result).containsExactly("app1", "app2")
    }

    @Test
    @DisplayName("앱 git config 조회는 ApplicationService에 위임한다")
    fun givenAppName_whenGetGitConfig_thenDelegatesToApplicationService() {
        // given
        val config = AppGitConfig("https://github.com/org/repo.git", "main")
        `when`(applicationService.getGitConfig("my-app")).thenReturn(config)

        // when
        val result = applicationFacade.getGitConfig("my-app")

        // then
        assertThat(result).isEqualTo(config)
    }

    @Test
    @DisplayName("Git URL과 branch가 있으면 앱 등록 후 백그라운드 checkout을 시작한다")
    fun givenGitUrlAndBranch_whenAddApp_thenStartsBackgroundCheckout() {
        // given
        val gitUrl = "https://github.com/org/repo.git"
        `when`(githubService.getToken()).thenReturn("github-token")
        `when`(repositoryService.preparePersistentRepository("my-app", gitUrl, "main", "github-token")).thenReturn(Path.of("/tmp/repo"))

        // when
        applicationFacade.addApp("my-app", gitUrl, "main")

        // then
        verify(applicationService).addApp("my-app", gitUrl, "main")
        verify(repositoryService).preparePersistentRepository("my-app", gitUrl, "main", "github-token")
    }

    @Test
    @DisplayName("Git URL이 없으면 백그라운드 checkout을 시작하지 않는다")
    fun givenBlankGitUrl_whenAddApp_thenDoesNotStartBackgroundCheckout() {
        // given
        val gitUrl = " "

        // when
        applicationFacade.addApp("my-app", gitUrl, "main")

        // then
        verify(applicationService).addApp("my-app", gitUrl, "main")
        verifyNoInteractions(repositoryService)
    }

    @Test
    @DisplayName("branch가 없으면 default branch로 백그라운드 checkout을 시작한다")
    fun givenBlankBranch_whenAddApp_thenStartsBackgroundCheckoutWithDefaultBranch() {
        // given
        val gitUrl = "https://github.com/org/repo.git"
        `when`(githubService.getToken()).thenReturn("github-token")
        `when`(repositoryService.preparePersistentRepository("my-app", gitUrl, "", "github-token")).thenReturn(Path.of("/tmp/repo"))

        // when
        applicationFacade.addApp("my-app", gitUrl, " ")

        // then
        verify(applicationService).addApp("my-app", gitUrl, " ")
        verify(repositoryService).preparePersistentRepository("my-app", gitUrl, "", "github-token")
    }

    @Test
    @DisplayName("deployBranch checkout 실패 후 default branch checkout이 성공하면 branch를 초기화하고 /topic/alert 메시지를 보낸다")
    fun givenInvalidDeployBranchAndDefaultCheckoutSucceeds_whenAddApp_thenClearsBranchAndPushesAlertMessage() {
        // given
        val gitUrl = "https://gitlab.com/org/repo.git"
        `when`(gitlabService.getToken()).thenReturn("gitlab-token")
        `when`(repositoryService.preparePersistentRepository("my-app", gitUrl, "main", "gitlab-token"))
            .thenThrow(IllegalStateException("branch not found"))
        `when`(repositoryService.preparePersistentRepository("my-app", gitUrl, "", "gitlab-token"))
            .thenReturn(Path.of("/tmp/repo"))
        var alertMessage: AlertMessage? = null
        doAnswer { invocation ->
            alertMessage = invocation.arguments[0] as AlertMessage
            null
        }.`when`(messageService).pushAlert(anyObject())

        // when
        applicationFacade.addApp("my-app", gitUrl, "main")

        // then
        verify(applicationService).saveGitConfig("my-app", gitUrl, null)
        assertThat(alertMessage?.type).isEqualTo(AlertMessageType.INVALID_DEPLOY_BRANCH_FALLBACK)
        assertThat(alertMessage?.applicationName).isEqualTo("my-app")
        assertThat(alertMessage?.deployBranch).isEqualTo("main")
        assertThat(alertMessage?.exceptionMessage).isEqualTo("branch not found")
    }

    @Test
    @DisplayName("deployBranch와 default branch checkout이 모두 실패하면 일반 checkout 실패 메시지를 보낸다")
    fun givenDeployBranchAndDefaultCheckoutFail_whenAddApp_thenPushesGeneralCheckoutFailureAlertMessage() {
        // given
        val gitUrl = "https://gitlab.com/org/repo.git"
        `when`(gitlabService.getToken()).thenReturn("gitlab-token")
        `when`(repositoryService.preparePersistentRepository("my-app", gitUrl, "main", "gitlab-token"))
            .thenThrow(IllegalStateException("branch not found"))
        `when`(repositoryService.preparePersistentRepository("my-app", gitUrl, "", "gitlab-token"))
            .thenThrow(IllegalStateException("authentication failed"))
        var alertMessage: AlertMessage? = null
        doAnswer { invocation ->
            alertMessage = invocation.arguments[0] as AlertMessage
            null
        }.`when`(messageService).pushAlert(anyObject())

        // when
        applicationFacade.addApp("my-app", gitUrl, "main")

        // then
        assertThat(alertMessage?.type).isEqualTo(AlertMessageType.SOURCE_CHECKOUT_FAILED)
        assertThat(alertMessage?.applicationName).isEqualTo("my-app")
        assertThat(alertMessage?.exceptionMessage).isEqualTo("authentication failed")
    }

    @Test
    @DisplayName("앱 수정 후 Git URL과 branch가 있으면 백그라운드 checkout을 시작한다")
    fun givenGitUrlAndBranch_whenUpdateApp_thenStartsBackgroundCheckout() {
        // given
        val gitUrl = "https://github.com/org/repo.git"
        `when`(githubService.getToken()).thenReturn("github-token")
        `when`(repositoryService.preparePersistentRepository("new-app", gitUrl, "main", "github-token")).thenReturn(Path.of("/tmp/repo"))

        // when
        applicationFacade.updateApp("old-app", "new-app", gitUrl, "main")

        // then
        verify(applicationService).updateApp("old-app", "new-app", gitUrl, "main")
        verify(repositoryService).preparePersistentRepository("new-app", gitUrl, "main", "github-token")
    }

    @Test
    @DisplayName("앱 이름 변경 시 기존 persistent repository를 삭제한 뒤 새 설정을 저장한다")
    fun givenRenamedAppWithExistingGitConfig_whenUpdateApp_thenDeletesPreviousPersistentRepositoryBeforeSavingNewConfig() {
        // given
        val oldGitUrl = "https://github.com/org/old-repo.git"
        val newGitUrl = "https://github.com/org/new-repo.git"
        `when`(applicationService.getGitConfig("old-app")).thenReturn(AppGitConfig(oldGitUrl, "main"))
        `when`(repositoryService.deletePersistentRepository("old-app", oldGitUrl)).thenReturn(true)
        `when`(githubService.getToken()).thenReturn("github-token")
        `when`(repositoryService.preparePersistentRepository("new-app", newGitUrl, "main", "github-token")).thenReturn(Path.of("/tmp/new-repo"))

        // when
        applicationFacade.updateApp("old-app", "new-app", newGitUrl, "main")

        // then
        val ordered = inOrder(repositoryService, applicationService)
        ordered.verify(repositoryService).deletePersistentRepository("old-app", oldGitUrl)
        ordered.verify(applicationService).updateApp("old-app", "new-app", newGitUrl, "main")
        verify(repositoryService).preparePersistentRepository("new-app", newGitUrl, "main", "github-token")
    }

    @Test
    @DisplayName("앱 삭제 시 Redis metadata 삭제 후 persistent repository를 삭제한다")
    fun givenAppWithGitConfig_whenRemoveApp_thenDeletesPersistentRepositoryAfterRemovingAppMetadata() {
        // given
        val gitUrl = "https://github.com/org/repo.git"
        `when`(applicationService.getGitConfig("my-app")).thenReturn(AppGitConfig(gitUrl, "main"))
        `when`(repositoryService.deletePersistentRepository("my-app", gitUrl)).thenReturn(true)

        // when
        applicationFacade.removeApp("my-app")

        // then
        val ordered = inOrder(applicationService, repositoryService)
        ordered.verify(applicationService).removeApp("my-app")
        ordered.verify(repositoryService).deletePersistentRepository("my-app", gitUrl)
    }

    @Test
    @DisplayName("앱 삭제는 ApplicationService에 위임한다")
    fun givenAppName_whenRemoveApp_thenDelegatesToApplicationService() {
        // given
        val appName = "my-app"

        // when
        applicationFacade.removeApp(appName)

        // then
        verify(applicationService).removeApp(appName)
    }
}
