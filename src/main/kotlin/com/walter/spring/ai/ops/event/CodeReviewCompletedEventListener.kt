package com.walter.spring.ai.ops.event

import com.walter.spring.ai.ops.service.ApplicationService
import com.walter.spring.ai.ops.service.SlackChannelService
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class CodeReviewCompletedEventListener(
    private val slackChannelService: SlackChannelService,
    private val applicationService: ApplicationService,
) {
    @EventListener
    fun onCodeReviewCompleted(event: CodeReviewCompletedEvent) {
        val applicationConfig = applicationService.getAppConfig(event.applicationName)
        if (applicationConfig == null || !applicationConfig.isSend) {
            return
        }
        val slackChannelPath = applicationConfig.slackChannel
        if (slackChannelPath.isNullOrBlank()) {
            return
        }
        slackChannelService.sendCodeReviewResult(event.review, slackChannelPath)
    }
}