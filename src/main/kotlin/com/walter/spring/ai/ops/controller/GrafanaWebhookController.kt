package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.code.AlertingStatus
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingRequest
import com.walter.spring.ai.ops.controller.dto.GrafanaAlertingResponse
import com.walter.spring.ai.ops.service.GrafanaService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/webhook")
class WebhookController(
    private val grafanaService: GrafanaService,
) {
    private val log = LoggerFactory.getLogger(WebhookController::class.java)

    @PostMapping(value = ["/grafana", "/grafana/{application}"])
    fun grafanaAlert(@RequestBody request: GrafanaAlertingRequest, @PathVariable application: String?): GrafanaAlertingResponse {
        val targetApplication = application ?: "Unknown Application"
        log.info(
            "Grafana webhook received — status: {}, alerts: {}, title: {}",
            request.status,
            request.alerts.size,
            request.title,
        )

        if (request.isResolved()) {
            log.info("Alert resolved — groupKey: {}", request.groupKey)
            return GrafanaAlertingResponse.of(AlertingStatus.RESOLVED)
        }

        request.alerts.filter { it.isFiring() }.forEach { alert ->
            log.info(
                "Firing alert — name: {}, fingerprint: {}, lokiLabels: {}, startsAt: {}",
                alert.alertName(),
                alert.fingerprint,
                alert.lokiLabels(),
                alert.startsAt,
            )
        }

        // TODO: Loki 로그 조회 + LLM 분석 연동
        return GrafanaAlertingResponse.of(AlertingStatus.FIRING)
    }
}
