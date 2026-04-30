package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.config.annotation.Facade
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

    fun getGitConfig(name: String) = applicationService.getGitConfig(name)

    fun addApp(name: String, gitUrl: String?, deployBranch: String?) {
        applicationService.addApp(name, gitUrl, deployBranch)
        checkoutSourceCodeInBackground(name, gitUrl, deployBranch)
    }

    fun updateApp(oldName: String, newName: String, gitUrl: String?, deployBranch: String?) {
        val previousConfig = applicationService.getGitConfig(oldName)
        val previousGitUrl = previousConfig?.gitUrl
        previousGitUrl
            ?.takeIf { shouldDeletePreviousRepository(oldName, newName, it, gitUrl) }
            ?.let { repositoryService.deletePersistentRepository(oldName, it) }
        applicationService.updateApp(oldName, newName, gitUrl, deployBranch)
        checkoutSourceCodeInBackground(newName, gitUrl, deployBranch)
    }

    fun removeApp(name: String) {
        val previousConfig = applicationService.getGitConfig(name)
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

    private fun checkoutSourceCodeInBackground(appName: String, gitUrl: String?, deployBranch: String?) {
        if (gitUrl.isNullOrBlank()) {
            return
        }
        CompletableFuture.runAsync({
            val accessToken = resolveAccessToken(gitUrl)
            try {
                val branch = deployBranch?.trim().orEmpty()
                repositoryService.preparePersistentRepository(appName, gitUrl, branch, accessToken)
            } catch (e: Exception) {
                if (deployBranch.isNullOrBlank()) {
                    log.warn("Background source code checkout failed — app: {}, branch: default, cause: {}", appName, e.message)
                    messageService.pushAlert(AlertMessage.checkoutFailed(appName, e))
                    return@runAsync
                }
                checkoutDefaultBranchAfterInvalidDeployBranch(appName, gitUrl, deployBranch, accessToken, e)
            }
        }, executor)
    }

    private fun checkoutDefaultBranchAfterInvalidDeployBranch(appName: String, gitUrl: String, deployBranch: String, accessToken: String?, branchFailure: Exception) {
        try {
            repositoryService.preparePersistentRepository(appName, gitUrl, StringUtils.EMPTY, accessToken)
            applicationService.saveGitConfig(appName, gitUrl, null)
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
