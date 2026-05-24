package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.AlertingStatus
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingResponse
import com.walter.spring.ai.ops.facade.IncidentAnalyzeFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Tag(name = "Webhook: Grafana", description = "Inbound webhook endpoint for Grafana Alerting. Returns immediately; incident analysis runs asynchronously on a virtual thread.",)
@RestController
@RequestMapping("/webhook")
class WebhookGrafana(
    private val incidentAnalyzeFacade: IncidentAnalyzeFacade,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    @Operation(
        summary = "Receive Grafana Alerting webhook",
        description = """
            Accepts a Grafana Alerting webhook payload (Contact Point → Webhook).

            Processing rules:
            - `status == resolved` or `status == nodata` → returns RESOLVED immediately without triggering analysis.
            - `status == firing` → triggers the async incident analysis pipeline:
              1. Fetch logs from Loki using alert label selectors.
              2. Optionally fetch metrics from Prometheus.
              3. Extract related source snippets from the cloned repository (if configured).
              4. Run LLM analysis combining alert context, logs, metrics, and source snippets.
              5. Persist the `AnalyzeFiringRecord` to Redis and push the result via WebSocket to `/topic/firing`.

            Use the optional `{application}` path segment to associate the alert with a specific registered application.

            Configure in Grafana: Contact Point → Webhook → URL `http://<host>:7079/webhook/grafana[/{application}]`.
        """
    )
    @PostMapping(value = ["/grafana", "/grafana/{application}"])
    fun grafanaAlert(
        @RequestBody request: GrafanaAlertingRequest,
        @Parameter(description = "Application name to associate this alert with (optional)")
        @PathVariable application: String?
    ): GrafanaAlertingResponse {
        if (request.isResolved() || request.isNoData()) {
            return GrafanaAlertingResponse.of(AlertingStatus.RESOLVED)
        }
        CompletableFuture.runAsync({ incidentAnalyzeFacade.analyzeFiring(request, application) }, executor)
        return GrafanaAlertingResponse.of(AlertingStatus.ACCEPTED)
    }
}