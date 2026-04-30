package com.walter.spring.ai.ops.util.dto

data class RedisLock(
    val key: String,
    val token: String,
)
