package com.walter.spring.ai.ops.config.exception

class UnauthorizedException(
    message: String = "Authentication is required. Please log in."
) : RuntimeException(message)