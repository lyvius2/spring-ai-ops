package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.service.AiModelService
import org.springframework.core.env.Environment
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class IndexController(
    private val aiModelService: AiModelService,
    private val environment: Environment,
) {
    @GetMapping("/")
    fun index(model: Model): String {
        model.addAttribute("configured", aiModelService.isConfigured())
        model.addAttribute("currentLlm", aiModelService.getCurrentLlm() ?: "")
        model.addAttribute("selectProvider", aiModelService.isSelectProviderRequired())
        model.addAttribute("activeProfile", environment.activeProfiles.firstOrNull() ?: "default")
        return "index"
    }
}
