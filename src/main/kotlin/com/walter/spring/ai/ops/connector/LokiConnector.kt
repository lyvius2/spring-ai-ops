package com.walter.spring.ai.ops.connector

import com.walter.spring.ai.ops.config.LokiConnectorConfig
import com.walter.spring.ai.ops.config.LokiConnectorConfig.Companion.PLACEHOLDER_URL
import com.walter.spring.ai.ops.connector.dto.LokiQueryInquiry
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.cloud.openfeign.SpringQueryMap
import org.springframework.web.bind.annotation.GetMapping

@FeignClient(name = "lokiConnector", url = PLACEHOLDER_URL, configuration = [LokiConnectorConfig::class], fallbackFactory = LokiConnectorFallbackFactory::class)
interface LokiConnector {
    @GetMapping("/loki/api/v1/query_range")
    fun queryRange(@SpringQueryMap inquiry: LokiQueryInquiry): LokiQueryResult
}
