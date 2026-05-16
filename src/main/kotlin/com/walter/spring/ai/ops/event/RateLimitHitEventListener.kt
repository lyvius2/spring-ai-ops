package com.walter.spring.ai.ops.event

import com.walter.spring.ai.ops.service.MessageService
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class RateLimitHitEventListener(
    private val messageService: MessageService
) {
    @EventListener
    fun onRateLimitHit(event: RateLimitHitEvent) {
        messageService.pushAnalysisStatus(
            "Rate limit reached (attempt ${event.attempt}/${event.maxRetries}). Waiting 61s before retry..."
        )
    }
}
