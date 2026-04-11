package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.service.LokiService
import feign.Client
import feign.Request
import org.springframework.context.annotation.Bean

class LokiConnectorConfig {
    companion object {
        const val PLACEHOLDER_URL = "http://loki-placeholder"
    }

    @Bean
    fun lokiClient(lokiService: LokiService): Client = Client { request, options ->
        val lokiUrl = lokiService.getLokiUrl()
        val resolvedRequest = Request.create(
            request.httpMethod(),
            request.url().replace(PLACEHOLDER_URL, lokiUrl),
            request.headers(),
            request.body(),
            request.charset(),
            request.requestTemplate(),
        )
        Client.Default(null, null).execute(resolvedRequest, options)
    }
}