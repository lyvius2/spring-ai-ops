package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Slack Block Kit block element.
 *
 * Slack API reference: https://api.slack.com/reference/block-kit/blocks
 *
 * Null fields are excluded from serialisation — Slack rejects unexpected null fields
 * in block payloads (e.g. `"block_id": null` causes `invalid_blocks`).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SlackBlock(
    val type: String,
    val text: SlackTextObject? = null,

    @JsonProperty("block_id")
    val blockId: String? = null,
) {
    companion object {
        private const val HEADER_MAX_LENGTH = 150

        fun header(text: String): SlackBlock {
            val safeText = if (text.length > HEADER_MAX_LENGTH) {
                text.take(HEADER_MAX_LENGTH)
            } else {
                text
            }
            return SlackBlock(type = "header", text = SlackTextObject(type = "plain_text", text = safeText))
        }

        fun section(mrkdwnText: String): SlackBlock {
            return SlackBlock(type = "section", text = SlackTextObject(type = "mrkdwn", text = mrkdwnText))
        }
    }
}
