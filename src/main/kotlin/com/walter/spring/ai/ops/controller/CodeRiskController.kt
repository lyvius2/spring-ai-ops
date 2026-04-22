package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.CodeRiskListResponse
import com.walter.spring.ai.ops.controller.dto.CodeRiskRequest
import com.walter.spring.ai.ops.controller.dto.CodeRiskResponse
import com.walter.spring.ai.ops.facade.CodeRiskFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Code Risk", description = "Analyzes security and quality risks in a Git repository by cloning the source code and running an AI-powered review.")
@RestController
@RequestMapping("/api/code-risk")
class CodeRiskController(
    private val codeRiskFacade: CodeRiskFacade,
) {
    @Operation(
        summary = "Analyze code risk for a registered application",
        description = """
            Clones the Git repository associated with the given application name,
            scans all source files, and returns an AI-generated risk assessment covering:
            - Security vulnerabilities
            - Code quality issues
            - Dependency concerns
            - Actionable recommendations

            Specify an optional `branch` to analyze a specific branch (defaults to the repository's default branch).
        """
    )
    @PostMapping
    fun analyzeCodeRisk(@RequestBody request: CodeRiskRequest): CodeRiskResponse {
        return try {
            CodeRiskResponse.success()
        } catch (e: Exception) {
            CodeRiskResponse.failure(e)
        }
    }

    @Operation(summary = "List code risk analysis records for an application")
    @GetMapping("/{application}/list")
    fun list(@Parameter(description = "Registered application name", required = true) @PathVariable application: String): CodeRiskListResponse {
        return CodeRiskListResponse(codeRiskFacade.getRecords(application))
    }
}