package com.walter.spring.ai.ops.facade

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.code.LlmProvider
import com.walter.spring.ai.ops.code.ObservabilityProvider
import com.walter.spring.ai.ops.config.CsrfTokenProvider
import com.walter.spring.ai.ops.config.annotation.Facade
import com.walter.spring.ai.ops.service.AiModelService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Facade
class DashboardFacade(
    private val aiModelService: AiModelService,
    private val environment: Environment,
    private val csrfTokenProvider: CsrfTokenProvider,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(DashboardFacade::class.java)

    fun getDashboardPuzzles(): HashMap<String, Any> {
        val (configuredFuture, currentLlmFuture, selectProviderFuture) = fetchAiModelFutures()
        val puzzles = HashMap<String, Any>()
        puzzles["configured"] = configuredFuture.join()
        puzzles["currentLlm"] = currentLlmFuture.join() ?: ""
        puzzles["selectProvider"] = selectProviderFuture.join()
        puzzles["activeProfile"] = environment.activeProfiles.firstOrNull() ?: "default"
        puzzles["llmProviders"] = LlmProvider.entries.toTypedArray()
        puzzles["gitRemoteProviders"] = GitRemoteProvider.entries.toTypedArray()
        puzzles["observabilityProviders"] = ObservabilityProvider.entries.toTypedArray()
        puzzles["csrfToken"] = csrfTokenProvider.token

        val auth = SecurityContextHolder.getContext().authentication
        val authenticated = auth != null && auth.isAuthenticated && auth !is AnonymousAuthenticationToken
        puzzles["loggedIn"] = authenticated
        puzzles["mainAdmin"] = authenticated && auth.name == "admin"
        return puzzles
    }

    private fun fetchAiModelFutures(): Triple<CompletableFuture<Boolean>, CompletableFuture<String?>, CompletableFuture<Boolean>> {
        val configuredFuture = CompletableFuture.supplyAsync({ aiModelService.isConfigured() }, executor)
            .exceptionally {
                log.error("Failed to check if LLM is configured", it)
                false
            }
        val currentLlmFuture = CompletableFuture.supplyAsync({ aiModelService.getCurrentLlm() }, executor)
            .exceptionally {
                log.error("Failed to get current LLM", it)
                null
            }
        val selectProviderFuture = CompletableFuture.supplyAsync({ aiModelService.isSelectProviderRequired() }, executor)
            .exceptionally {
                log.error("Failed to check if select provider is required", it)
                false
            }
        return Triple(configuredFuture, currentLlmFuture, selectProviderFuture)
    }
}
