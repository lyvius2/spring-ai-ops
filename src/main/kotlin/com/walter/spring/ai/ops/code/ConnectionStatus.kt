package com.walter.spring.ai.ops.code

enum class ConnectionStatus(
    val description: String,
    val message: String,
) {
    READY(
        description = "already_configured",
        message = "연결되어 있습니다 : ",
    ),
    SUCCESS(
        description = "connected",
        message = "성공적으로 연결되었습니다 : ",
    ),
    FAILURE(
        description = "failure",
        message = "Unknown Error.",
    ),
}
