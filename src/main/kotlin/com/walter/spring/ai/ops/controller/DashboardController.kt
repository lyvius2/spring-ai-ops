package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.facade.DashboardFacade
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Hidden
@Controller
class DashboardController(
    private val dashboardFacade: DashboardFacade,
) {
    @GetMapping("/")
    fun dashboard(model: Model): String {
        model.addAttribute("data", dashboardFacade.getDashboardPuzzles())
        return "index"
    }
}
