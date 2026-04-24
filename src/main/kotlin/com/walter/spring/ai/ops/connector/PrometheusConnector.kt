package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.config.PrometheusConnectorConfig
import com.walter.spring.ai.ops.config.PrometheusConnectorConfig.Companion.PLACEHOLDER_URL
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryInquiry
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.cloud.openfeign.SpringQueryMap
import org.springframework.web.bind.annotation.GetMapping

@FeignClient(name = "prometheusConnector", url = PLACEHOLDER_URL, configuration = [PrometheusConnectorConfig::class], fallbackFactory = PrometheusConnectorFallbackFactory::class)
interface PrometheusConnector {
    @GetMapping("/api/v1/query_range")
    fun queryRange(@SpringQueryMap inquiry: PrometheusQueryInquiry): PrometheusQueryResult
}

