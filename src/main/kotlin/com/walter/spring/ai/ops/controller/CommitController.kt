package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.CommitListResponse
import com.walter.spring.ai.ops.service.GithubService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Code Review", description = "Retrieve LLM-generated code review records per application")
@RestController
@RequestMapping("/api/commit")
class CommitController(
    private val githubService: GithubService,
) {
    @Operation(
        summary = "List code review records for an application",
        description = "Returns the most recent `maximum-view-count` records (newest first). Records are kept for `data-retention-hours` hours."
    )
    @GetMapping("/{application}/list")
    fun list(
        @Parameter(description = "Registered application name", required = true)
        @PathVariable application: String
    ): CommitListResponse {
        return CommitListResponse(githubService.getCodeReviewRecords(application))
    }
}
