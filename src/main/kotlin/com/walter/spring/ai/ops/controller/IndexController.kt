package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.service.AiClientService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class IndexController(
    private val aiClientService: AiClientService,
) {
    @GetMapping("/")
    fun index(model: Model): String {
        model.addAttribute("configured", aiClientService.isConfigured())
        model.addAttribute("currentLlm", aiClientService.getCurrentLlm() ?: "")
        return "index"
    }
}
