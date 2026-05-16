package com.walter.spring.ai.ops.connector.dto

/**
 * Slack Incoming Webhook response.
 *
 * Slack returns a plain-text body:
 * - `"ok"` on success (HTTP 200)
 * - an error string (e.g. `"invalid_payload"`) on failure
 */
data class SlackMessageResponse(
    val body: String,
) {
    fun isSuccess(): Boolean = body == "ok"
}

