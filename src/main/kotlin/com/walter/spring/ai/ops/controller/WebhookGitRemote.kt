package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.controller.dto.GithubPushRequest
import com.walter.spring.ai.ops.controller.dto.GithubPushResponse
import com.walter.spring.ai.ops.facade.CodeReviewFacade
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

@Tag(name = "Webhook: Git Remote", description = "Inbound webhook endpoint for GitHub and GitLab push events. Returns immediately; code review runs asynchronously on a virtual thread.")
@RestController
@RequestMapping("/webhook")
class WebhookGitRemote(
    private val codeReviewFacade: CodeReviewFacade,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    @Operation(
        summary = "Receive Git Remote push webhook (GitHub / GitLab)",
        description = """
            Accepts a push event payload from GitHub or GitLab.

            Provider detection is performed via request headers:
            - `X-GitHub-Event` present → treated as a GitHub push event.
            - `X-Gitlab-Event` present → treated as a GitLab push event.
            - Neither header present → request is rejected with `400 Bad Request`.

            Processing pipeline (asynchronous):
            1. Fetch the commit diff via the GitHub Commits API or GitLab Commits API.
            2. Run LLM code review on the changed files.
            3. Persist the `CodeReviewRecord` to Redis.
            4. Push the result via WebSocket to `/topic/commit`.
            5. Publish a `CodeReviewCompletedEvent` — triggers a Slack notification if the application has Slack configured.

            If no commits are present (e.g. ping or branch-deletion event), the request is silently ignored.
            If the Git token is not configured, an error record is saved and pushed instead of a review result.

            Use the optional `{application}` path segment to associate the push with a specific registered application.

            Configure in GitHub/GitLab: Webhooks → Payload URL `http://<host>:7079/webhook/git[/{application}]`, Content-Type `application/json`, Events: `push`.
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
                githubEvent != null -> GithubPushRequest.fromGithubBody(body).copy(source = GitRemoteProvider.GITHUB)
                gitlabEvent != null -> GithubPushRequest.fromGitlabBody(body).copy(source = GitRemoteProvider.GITLAB)
                else -> throw IllegalArgumentException("Unsupported event type: missing X-GitHub-Event or X-Gitlab-Event header") }
            codeReviewFacade.analyzeCodeDiffer(request, application) }, executor)
        return GithubPushResponse.accepted()
    }
}
