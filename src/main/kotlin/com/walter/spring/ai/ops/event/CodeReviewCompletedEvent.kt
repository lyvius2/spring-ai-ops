package com.walter.spring.ai.ops.event

import com.walter.spring.ai.ops.record.CodeReviewRecord
import org.springframework.context.ApplicationEvent

class CodeReviewCompletedEvent(
    val review: CodeReviewRecord,
    val applicationName: String,
) : ApplicationEvent(review)