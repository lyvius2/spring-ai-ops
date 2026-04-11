package com.walter.spring.ai.ops.controller.dto

import java.lang.Exception

data class LokiConfigResponse private constructor(
    val status: String,
    val message: String = "",
) {
    companion object {
        fun success(): LokiConfigResponse = LokiConfigResponse("OK")
        fun failure(e: Exception): LokiConfigResponse = LokiConfigResponse("ERROR", e.message ?: "Unknown error.")
    }
}
