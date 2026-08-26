package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.CommitListResponse
import com.walter.spring.ai.ops.service.GithubService
import com.walter.spring.ai.ops.controller.dto.GitBranchListResponse
import com.walter.spring.ai.ops.controller.dto.GitCommitHistoryResponse
import com.walter.spring.ai.ops.controller.dto.ManualCodeReviewRequest
import com.walter.spring.ai.ops.controller.dto.ManualCodeReviewResponse
import com.walter.spring.ai.ops.facade.CodeReviewFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.beans.factory.annotation.Qualifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@Tag(name = "Code Review", description = "Retrieve LLM-generated code review records per application")
@RestController
@RequestMapping("/api/commit")
class CommitController(
    private val githubService: GithubService,
    private val codeReviewFacade: CodeReviewFacade,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    @GetMapping("/{application}/branches")
    fun branches(@Parameter(description = "Registered application name", required = true) @PathVariable application: String): GitBranchListResponse {
        return GitBranchListResponse(codeReviewFacade.getBranches(application))
    }

    @GetMapping("/{application}/history")
    fun history(@Parameter(description = "Registered application name", required = true) @PathVariable application: String, @RequestParam branch: String): GitCommitHistoryResponse {
        return GitCommitHistoryResponse(branch, codeReviewFacade.getCommitHistory(application, branch))
    }

    @PostMapping("/{application}/review")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun review(@Parameter(description = "Registered application name", required = true) @PathVariable application: String, @RequestBody request: ManualCodeReviewRequest): ManualCodeReviewResponse {
        codeReviewFacade.validateManualReview(application, request)
        CompletableFuture.runAsync({ codeReviewFacade.analyzeManualReview(application, request) }, executor)
        return ManualCodeReviewResponse(true, "Code Review has started. Please check the result on this screen in a few minutes.")
    }

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
