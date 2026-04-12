package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.AlertingStatus
import com.walter.spring.ai.ops.controller.dto.GithubPushRequest
import com.walter.spring.ai.ops.controller.dto.GithubPushResponse
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingResponse
import com.walter.spring.ai.ops.facade.AnalyzeFacade
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@RestController
@RequestMapping("/webhook")
class WebhookController(
    private val analyzeFacade: AnalyzeFacade,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    @PostMapping(value = ["/grafana", "/grafana/{application}"])
    fun grafanaAlert(@RequestBody request: GrafanaAlertingRequest, @PathVariable application: String?): GrafanaAlertingResponse {
        if (request.isResolved()) {
            return GrafanaAlertingResponse.of(AlertingStatus.RESOLVED)
        }
        CompletableFuture.runAsync({ analyzeFacade.analyzeFiring(request, application) }, executor)
        return GrafanaAlertingResponse.of(AlertingStatus.ACCEPTED)
    }

    @PostMapping(value = ["/github", "/github/{application}"])
    fun githubPush(@RequestBody request: GithubPushRequest, @PathVariable application: String?): GithubPushResponse {
        CompletableFuture.runAsync({ analyzeFacade.analyzeCodeDiffer(request, application) }, executor)
        return GithubPushResponse.accepted()
    }
}
