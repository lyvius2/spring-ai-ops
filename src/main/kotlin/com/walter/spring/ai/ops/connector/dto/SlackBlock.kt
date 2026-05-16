package com.walter.spring.ai.ops.connector.dto
import com.fasterxml.jackson.annotation.JsonProperty
/**
 * Slack Block Kit block element.
 *
 * Slack API reference: https://api.slack.com/reference/block-kit/blocks
 */
data class SlackBlock(
    val type: String,
    val text: SlackTextObject? = null,

    @JsonProperty("block_id")
    val blockId: String? = null,
) {
    companion object {
        fun header(text: String): SlackBlock {
            return SlackBlock(type = "header", text = SlackTextObject(type = "plain_text", text = text))
        }
        fun section(mrkdwnText: String): SlackBlock {
            return SlackBlock(type = "section", text = SlackTextObject(type = "mrkdwn", text = mrkdwnText))
        }
    }
}
