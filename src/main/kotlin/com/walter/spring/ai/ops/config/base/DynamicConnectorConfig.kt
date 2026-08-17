package com.walter.spring.ai.ops.config.base

import feign.Client
import feign.Request
import feign.okhttp.OkHttpClient
import com.walter.spring.ai.ops.connector.cache.CacheStorePort
import okhttp3.ConnectionPool
import org.springframework.context.annotation.Bean
import java.util.concurrent.TimeUnit

abstract class DynamicConnectorConfig {
    protected abstract val cacheStorePort: CacheStorePort
    protected abstract val configuredUrl: String
    protected abstract val redisUrlKey: String
    protected abstract val connectTimeout: Long
    protected abstract val readTimeout: Long

    abstract val placeholderUrl: String

    protected open fun resolveUrl(): String =
        cacheStorePort.get(redisUrlKey)?.takeIf { it.isNotBlank() }
            ?: configuredUrl

    protected val httpClient: OkHttpClient = OkHttpClient(SHARED_HTTP_CLIENT)

    @Bean
    fun feignOptions(): Request.Options =
        Request.Options(connectTimeout, TimeUnit.MILLISECONDS, readTimeout, TimeUnit.MILLISECONDS, true)

    @Bean
    fun externalClient(): Client = Client { request, options ->
        val resolvedUrl = resolveUrl()
        val resolvedRequest = Request.create(
            request.httpMethod(),
            request.url().replace(placeholderUrl, resolvedUrl),
            request.headers(),
            request.body(),
            request.charset(),
            request.requestTemplate(),
        )
        httpClient.execute(resolvedRequest, options)
    }

    companion object {
        private val SHARED_HTTP_CLIENT = okhttp3.OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 1, TimeUnit.MINUTES))
            .build()
    }
}
