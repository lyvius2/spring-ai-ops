package com.walter.spring.ai.ops.config

import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CsrfTokenProvider {
    val token: String = UUID.randomUUID().toString().replace("-", "")
}

