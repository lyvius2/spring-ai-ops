package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.CommitListResponse
import com.walter.spring.ai.ops.service.GithubService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/commit")
class CommitController(
    private val githubService: GithubService,
) {
    @GetMapping("/{application}/list")
    fun list(@PathVariable application: String): CommitListResponse {
        return CommitListResponse(githubService.getCodeReviewRecords(application))
    }
}
