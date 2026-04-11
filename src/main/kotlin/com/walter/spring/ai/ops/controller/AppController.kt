package com.walter.spring.ai.ops.controller

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/apps")
class AppController(
    private val redisTemplate: StringRedisTemplate,
) {
    @GetMapping
    fun getApps(): ResponseEntity<List<String>> {
        val apps = redisTemplate.opsForList().range("apps", 0, -1) ?: emptyList()
        return ResponseEntity.ok(apps)
    }
}