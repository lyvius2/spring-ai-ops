package com.walter.spring.ai.ops.config.exception

class InvalidPullRequestException(
    message: String
) : RuntimeException("PR webhook skipped — $message")
