package com.walter.spring.ai.ops.connector.dto

/**
 * Slack text composition object used inside Block Kit blocks.
 *
 * Slack API reference: https://api.slack.com/reference/block-kit/composition-objects#text
 */
data class SlackTextObject(
    val type: String = "mrkdwn",
    val text: String,
    val emoji: Boolean? = null,
)

