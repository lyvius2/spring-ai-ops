package com.walter.spring.ai.ops.connector.cache

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class ImMemoryCacheStoreConnector : CacheStorePort {
    private val values = ConcurrentHashMap<String, String>()
    private val sets = ConcurrentHashMap<String, MutableSet<String>>()
    private val timeOrderedSets = ConcurrentHashMap<String, ConcurrentHashMap<String, Double>>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String) {
        values[key] = value
    }

    override fun delete(key: String): Boolean {
        val valueRemoved = values.remove(key) != null
        val setRemoved = sets.remove(key) != null
        val timeOrderedSetRemoved = timeOrderedSets.remove(key) != null
        return valueRemoved || setRemoved || timeOrderedSetRemoved
    }

    override fun getSet(key: String): Set<String> = sets[key]?.toSet() ?: emptySet()

    override fun addToSet(key: String, value: String) {
        sets.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(value)
    }

    override fun removeFromSet(key: String, value: String) {
        sets[key]?.remove(value)
    }

    override fun addToTimeOrderedSet(key: String, value: String, retentionHours: Long) {
        timeOrderedSets.computeIfAbsent(key) { ConcurrentHashMap() }[value] = Instant.now().toEpochMilli().toDouble()
    }

    override fun getTimeOrderedSetDescending(key: String): List<String> =
        timeOrderedSets[key]
            ?.entries
            ?.sortedByDescending { it.value }
            ?.map { it.key }
            ?: emptyList()

    override fun getTimeOrderedSetSize(key: String): Long = timeOrderedSets[key]?.size?.toLong() ?: 0L

    override fun <T> withLock(
        key: String,
        ttl: Duration,
        waitTimeout: Duration,
        retryInterval: Duration,
        block: () -> T,
    ): T {
        require(key.isNotBlank()) { "Cache lock key must not be blank." }
        require(!waitTimeout.isNegative) { "Cache lock wait timeout must not be negative." }
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        if (!lock.tryLock(waitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Failed to acquire cache lock '$key' within ${waitTimeout.toMillis()}ms.")
        }
        try {
            return block()
        } finally {
            lock.unlock()
            if (!lock.isLocked && !lock.hasQueuedThreads()) {
                locks.remove(key, lock)
            }
        }
    }
}
