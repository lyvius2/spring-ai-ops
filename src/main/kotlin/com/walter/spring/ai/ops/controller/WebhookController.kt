package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.AlertingStatus
import com.walter.spring.ai.ops.controller.dto.GithubPushRequest
import com.walter.spring.ai.ops.controller.dto.GithubPushResponse
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingResponse
import com.walter.spring.ai.ops.facade.AnalyzeFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Tag(name = "Webhook", description = "Inbound webhooks from Grafana Alerting and GitHub. Each request returns immediately; analysis runs asynchronously on a virtual thread.")
@RestController
@RequestMapping("/webhook")
class WebhookController(
    private val analyzeFacade: AnalyzeFacade,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    @Operation(
        summary = "Receive Grafana Alerting webhook",
        description = """
            Accepts a Grafana webhook payload.
            - `status == resolved` → returns RESOLVED immediately (no analysis).
            - `status == firing`  → triggers async pipeline: Loki log query → LLM analysis → WebSocket push to `/topic/firing`.

            Optionally tag the alert to a specific application via the `{application}` path segment.
        """
    )
    @PostMapping(value = ["/grafana", "/grafana/{application}"])
    fun grafanaAlert(
        @RequestBody request: GrafanaAlertingRequest,
        @Parameter(description = "Application name to associate this alert with (optional)")
        @PathVariable application: String?
    ): GrafanaAlertingResponse {
        if (request.isResolved()) {
            return GrafanaAlertingResponse.of(AlertingStatus.RESOLVED)
        }
        CompletableFuture.runAsync({ analyzeFacade.analyzeFiring(request, application) }, executor)
        return GrafanaAlertingResponse.of(AlertingStatus.ACCEPTED)
    }

    @Operation(
        summary = "Receive Git Remote push webhook (GitHub/GitLab)",
        description = """
            Accepts a Git Remote push event payload.
            Triggers async pipeline: Git Remote Commits API → LLM code review → WebSocket push to `/topic/commit`.

            Optionally tag the push to a specific application via the `{application}` path segment.
        """
    )
    @PostMapping(value = ["/git", "/git/{application}"])
    fun githubPush(
        @RequestHeader(value = "X-GitHub-Event", required = false) githubEvent: String?,
        @RequestHeader(value = "X-Gitlab-Event", required = false) gitlabEvent: String?,
        @RequestBody body: Map<String, Any>,
        @Parameter(description = "Application name to associate this push with (optional)")
        @PathVariable application: String?
    ): GithubPushResponse {
        CompletableFuture.runAsync({
            val request = when {
                githubEvent != null -> GithubPushRequest.fromGithubBody(body)
                gitlabEvent != null -> GithubPushRequest.fromGitlabBody(body)
                else -> throw IllegalArgumentException("Unsupported event type: missing X-GitHub-Event or X-Gitlab-Event header") }
            analyzeFacade.analyzeCodeDiffer(request, application) }, executor)
        return GithubPushResponse.accepted()
    }
}
