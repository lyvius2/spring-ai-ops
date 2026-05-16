package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.connector.SlackChannelConnector
import org.springframework.stereotype.Service

@Service
class SlackChannelService(
    private val slackChannelConnector: SlackChannelConnector,
) {
}