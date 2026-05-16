package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.connector.dto.SlackMessageRequest
import com.walter.spring.ai.ops.connector.dto.SlackMessageResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Component
class SlackChannelConnector(
    @Value("\${slack.url}")
    private val slackUrl: String,
) {
    private val client = RestClient.create(slackUrl)

    fun sendMessage(message: SlackMessageRequest, channelPath: String): SlackMessageResponse {
        val body = client.post()
            .uri(channelPath)
            .contentType(MediaType.APPLICATION_JSON)
            .body(message)
            .retrieve()
            .body<String>() ?: throw RuntimeException("Failed to send message to Slack")
        return SlackMessageResponse(body)
    }
}