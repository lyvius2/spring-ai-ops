package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Slack text composition object used inside Block Kit blocks.
 *
 * Slack API reference: https://api.slack.com/reference/block-kit/composition-objects#text
 *
 * Null fields are excluded from serialisation — Slack rejects unknown null fields in some block types.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SlackTextObject(
    val type: String = "mrkdwn",
    val text: String,
    val emoji: Boolean? = null,
)

