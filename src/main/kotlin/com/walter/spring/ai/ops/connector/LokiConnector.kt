package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.connector.LokiConnectorConfig.Companion.PLACEHOLDER_URL
import com.walter.spring.ai.ops.connector.dto.LokiQueryInquiry
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import org.slf4j.LoggerFactory
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.cloud.openfeign.SpringQueryMap
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping

@FeignClient(
    name = "lokiConnector",
    url = PLACEHOLDER_URL,
    configuration = [LokiConnectorConfig::class],
    fallbackFactory = LokiConnectorFallbackFactory::class,
)
interface LokiConnector {

    @GetMapping("/loki/api/v1/query_range")
    fun queryRange(@SpringQueryMap inquiry: LokiQueryInquiry): LokiQueryResult
}

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
