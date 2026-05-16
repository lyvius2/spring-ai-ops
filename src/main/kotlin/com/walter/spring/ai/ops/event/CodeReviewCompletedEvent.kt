package com.walter.spring.ai.ops.event

import com.walter.spring.ai.ops.record.CodeReviewRecord
import org.springframework.context.ApplicationEvent

class CodeReviewCompletedEvent(
    private val review: CodeReviewRecord,
    private val applicationName: String,
) : ApplicationEvent(review)