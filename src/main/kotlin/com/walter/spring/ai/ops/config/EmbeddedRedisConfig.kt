package com.walter.spring.ai.ops.config

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import redis.embedded.RedisServer

@Configuration
class EmbeddedRedisConfig(
    @Value("\${spring.data.redis.url:}") private val redisUrl: String,
) {
    private val log: Logger = LoggerFactory.getLogger(EmbeddedRedisConfig::class.java)
    private var redisServer: RedisServer? = null

    @PostConstruct
    fun startRedisServer() {
        if (redisUrl.isNotBlank()) {
            log.info("External Redis URL is configured — skipping embedded Redis startup")
            return
        }
        try {
            redisServer = RedisServer.newRedisServer().build()
            redisServer!!.start()
        } catch (e: Exception) {
            log.error("Exception occurred while setting up embedded redis : {}", e.message)
        }
    }

    @PreDestroy
    fun stopRedisServer() {
        try {
            if (redisServer != null) {
                redisServer!!.stop()
            }
        } catch (e: Exception) {
            log.error("Embedded Redis stopped on failed : {}", e.message)
        }
    }
}