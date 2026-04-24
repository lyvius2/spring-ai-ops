package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.connector.dto.PrometheusQueryInquiry
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult
import org.slf4j.LoggerFactory
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component

@Component
class PrometheusConnectorFallbackFactory : FallbackFactory<PrometheusConnector> {
    private val log = LoggerFactory.getLogger(PrometheusConnectorFallbackFactory::class.java)

    override fun create(cause: Throwable): PrometheusConnector {
        return object : PrometheusConnector {
            override fun queryRange(inquiry: PrometheusQueryInquiry): PrometheusQueryResult {
                log.error("Prometheus query failed: {}", cause.message, cause)
                return PrometheusQueryResult(errorMessage = cause.message ?: "Failed to connect to Prometheus.")
            }
        }
    }
}

