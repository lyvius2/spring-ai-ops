package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_LOKI_URL
import com.walter.spring.ai.ops.connector.LokiConnector
import com.walter.spring.ai.ops.connector.cache.CacheStorePort
import com.walter.spring.ai.ops.connector.dto.LokiQueryInquiry
import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.util.extension.verifyHttpConnection
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI

@Service
class LokiService(
    private val cacheStorePort: CacheStorePort,
    private val lokiConnector: LokiConnector,
    @Value("\${loki.url:}") private val lokiUrlFromConfig: String,
) {
    fun isConfigured(): Boolean = getLokiUrl().isNotBlank()

    fun getLokiUrl(): String =
        cacheStorePort.get(REDIS_KEY_LOKI_URL)?.takeIf { it.isNotBlank() }
            ?: lokiUrlFromConfig

    fun setLokiUrl(lokiUrl: String) {
        URI(lokiUrl).verifyHttpConnection()
        cacheStorePort.set(REDIS_KEY_LOKI_URL, lokiUrl)
    }

    fun executeLogQuery(request: LokiQueryInquiry): LokiQueryResult {
        return lokiConnector.queryRange(request)
    }
}
