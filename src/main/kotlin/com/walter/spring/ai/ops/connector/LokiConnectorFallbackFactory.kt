package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.connector.dto.LokiQueryInquiry
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import org.slf4j.LoggerFactory
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component

@Component
class LokiConnectorFallbackFactory : FallbackFactory<LokiConnector> {
    private val log = LoggerFactory.getLogger(LokiConnectorFallbackFactory::class.java)
    override fun create(cause: Throwable): LokiConnector {
        return object : LokiConnector {
            override fun queryRange(inquiry: LokiQueryInquiry): LokiQueryResult {
                log.error("Loki query failed: {}", cause.message, cause)
                return LokiQueryResult(
                    errorMessage = cause.message ?: "Failed to connect to Loki.",
                )
            }
        }
    }
}
