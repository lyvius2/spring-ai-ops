package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.code.LlmProvider
import com.walter.spring.ai.ops.code.ObservabilityProvider
import com.walter.spring.ai.ops.config.CsrfTokenProvider
import com.walter.spring.ai.ops.service.AiModelService
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.core.env.Environment
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Hidden
@Controller
class IndexController(
    private val aiModelService: AiModelService,
    private val environment: Environment,
    private val csrfTokenProvider: CsrfTokenProvider,
) {
    @GetMapping("/")
    fun index(model: Model): String {
        model.addAttribute("configured", aiModelService.isConfigured())
        model.addAttribute("currentLlm", aiModelService.getCurrentLlm() ?: "")
        model.addAttribute("selectProvider", aiModelService.isSelectProviderRequired())
        model.addAttribute("activeProfile", environment.activeProfiles.firstOrNull() ?: "default")
        model.addAttribute("llmProviders", LlmProvider.entries.toTypedArray())
        model.addAttribute("gitRemoteProviders", GitRemoteProvider.entries.toTypedArray())
        model.addAttribute("observabilityProviders", ObservabilityProvider.entries.toTypedArray())
        model.addAttribute("csrfToken", csrfTokenProvider.token)
        return "index"
    }
}
