package com.walter.spring.ai.ops.connector.cache

import java.time.Duration

interface CacheStorePort {
    fun get(key: String): String?

    fun set(key: String, value: String)

    fun delete(key: String): Boolean

    fun getDataSet(key: String): Set<String>

    fun addToSet(key: String, value: String)

    fun removeFromSet(key: String, value: String)

    fun addToTimeOrderedSet(key: String, value: String, retentionHours: Long)

    fun getTimeOrderedSetDescending(key: String): List<String>

    fun getTimeOrderedSetSize(key: String): Long

    fun <T> withLock(
        key: String,
        ttl: Duration,
        waitTimeout: Duration,
        retryInterval: Duration,
        block: () -> T,
    ): T
}
