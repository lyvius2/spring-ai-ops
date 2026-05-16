package com.walter.spring.ai.ops.event

import org.springframework.context.ApplicationEvent

class RateLimitHitEvent(
    source: Any,
    val attempt: Int,
    val maxRetries: Int
) : ApplicationEvent(source)
