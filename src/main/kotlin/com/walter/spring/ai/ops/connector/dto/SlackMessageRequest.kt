package com.walter.spring.ai.ops.connector.dto

/**
 * Request payload for Slack Incoming Webhook.
 *
 * Slack API reference: https://api.slack.com/messaging/webhooks#posting_with_webhooks
 *
 * @param text Fallback text shown in notifications and when blocks cannot be rendered. Required.
 * @param blocks Optional Block Kit blocks for rich message formatting.
 *               When provided, [text] is used only as the notification fallback.
 * @param mrkdwn Whether to enable mrkdwn parsing in the top-level [text] field.
 *               Defaults to true. Has no effect when [blocks] is used.
 */
data class SlackMessageRequest(
    val text: String,
    val blocks: List<SlackBlock>? = null,
    val mrkdwn: Boolean = true,
)
