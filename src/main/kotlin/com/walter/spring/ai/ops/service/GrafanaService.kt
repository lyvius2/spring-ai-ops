package com.walter.spring.ai.ops.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class FiringAnalysisService(
    @Value("\${loki.url:}") private val lokiUrl: String,
) {
}