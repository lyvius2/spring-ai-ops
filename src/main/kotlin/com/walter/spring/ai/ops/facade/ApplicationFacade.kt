package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.service.GitlabService
import com.walter.spring.ai.ops.service.MessageService
import com.walter.spring.ai.ops.service.RepositoryService
import com.walter.spring.ai.ops.service.dto.AlertMessage
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Facade
class ApplicationFacade(
    private val applicationService: ApplicationService,
    private val repositoryService: RepositoryService,
    private val githubService: GithubService,
    private val gitlabService: GitlabService,
    private val messageService: MessageService,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(ApplicationFacade::class.java)

    fun getApps(): List<String> = applicationService.getApps()

    fun getAppConfig(name: String) = applicationService.getAppConfig(name)

    fun addApp(appUpdateRequest: AppUpdateRequest) {
        applicationService.addApp(appUpdateRequest)
        checkoutSourceCodeInBackground(appUpdateRequest)
    }

    fun updateApp(oldName: String, appUpdateRequest: AppUpdateRequest) {
        val previousConfig = applicationService.getAppConfig(oldName)
        val previousGitUrl = previousConfig?.gitUrl
        previousGitUrl
            ?.takeIf { shouldDeletePreviousRepository(oldName, appUpdateRequest.name, it, appUpdateRequest.gitUrl) }
            ?.let { repositoryService.deletePersistentRepository(oldName, it) }
        applicationService.updateApp(oldName, appUpdateRequest)
        checkoutSourceCodeInBackground(appUpdateRequest)
    }

    fun removeApp(name: String) {
        val previousConfig = applicationService.getAppConfig(name)
        applicationService.removeApp(name)
        val previousGitUrl = previousConfig?.gitUrl
        if (!previousGitUrl.isNullOrBlank()) {
            repositoryService.deletePersistentRepository(name, previousGitUrl)
        }
    }

    private fun shouldDeletePreviousRepository(oldName: String, newName: String, previousGitUrl: String?, newGitUrl: String?): Boolean {
        if (previousGitUrl.isNullOrBlank()) {
            return false
        }
        return oldName != newName || previousGitUrl != newGitUrl
    }

    private fun checkoutSourceCodeInBackground(appUpdateRequest: AppUpdateRequest) {
        val appName = appUpdateRequest.name
        if (appUpdateRequest.gitUrl.isNullOrBlank()) {
            return
        }
        CompletableFuture.runAsync({
            val gitUrl = appUpdateRequest.gitUrl
            val accessToken = resolveAccessToken(gitUrl)
            try {
                val branch = appUpdateRequest.deployBranch?.trim().orEmpty()
                repositoryService.preparePersistentRepository(appName, gitUrl, branch, accessToken)
            } catch (e: Exception) {
                if (appUpdateRequest.deployBranch.isNullOrBlank()) {
                    log.warn("Background source code checkout failed — app: {}, branch: default, cause: {}", appName, e.message)
                    messageService.pushAlert(AlertMessage.checkoutFailed(appName, e))
                    return@runAsync
                }
                checkoutDefaultBranchAfterInvalidDeployBranch(appUpdateRequest, accessToken, e)
            }
        }, executor)
    }

    private fun checkoutDefaultBranchAfterInvalidDeployBranch(appUpdateRequest: AppUpdateRequest, accessToken: String?, branchFailure: Exception) {
        val appName = appUpdateRequest.name
        val gitUrl = appUpdateRequest.gitUrl ?: StringUtils.EMPTY
        val deployBranch = appUpdateRequest.deployBranch ?: StringUtils.EMPTY
        try {
            repositoryService.preparePersistentRepository(appName, gitUrl, StringUtils.EMPTY, accessToken)
            appUpdateRequest.deployBranch = null
            applicationService.saveAppConfig(appUpdateRequest)
            messageService.pushAlert(AlertMessage.fallbackFailed(appName, deployBranch, branchFailure))
        } catch (defaultFailure: Exception) {
            log.warn(
                "Background source code checkout failed for both deploy branch and default branch — app: {}, deployBranch: {}, deployBranchCause: {}, defaultCause: {}",
                appName,
                deployBranch,
                branchFailure.message,
                defaultFailure.message,
            )
            messageService.pushAlert(AlertMessage.checkoutFailed(appName, defaultFailure))
        }
    }

    private fun resolveAccessToken(gitUrl: String): String? {
        val lower = gitUrl.lowercase()
        return when {
            lower.contains("github") -> githubService.getToken()
            lower.contains("gitlab") -> gitlabService.getToken()
            else -> null
        }
    }
}
