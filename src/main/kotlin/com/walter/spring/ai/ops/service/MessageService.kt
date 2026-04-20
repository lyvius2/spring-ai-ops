package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.record.AnalyzeFiringRecord
import com.walter.spring.ai.ops.record.CodeReviewRecord
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service

@Service
class MessageService(private val messagingTemplate: SimpMessagingTemplate) {

    fun pushFiring(record: AnalyzeFiringRecord) {
        messagingTemplate.convertAndSend("/topic/firing", record)
    }

    fun pushCodeReview(record: CodeReviewRecord) {
        messagingTemplate.convertAndSend("/topic/commit", record)
    }

    fun pushAnalysisStatus(message: String) {
        messagingTemplate.convertAndSend("/topic/analysis/status", message)
    }
}
