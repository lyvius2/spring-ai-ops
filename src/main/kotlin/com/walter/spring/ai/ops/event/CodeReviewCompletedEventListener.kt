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
        // TODO : Inquiry set Slack channel path
        // TODO : Implement logic to send message to Slack using slackChannelService
    }
}