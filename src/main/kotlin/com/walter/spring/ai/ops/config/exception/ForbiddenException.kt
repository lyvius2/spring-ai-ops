package com.walter.spring.ai.ops.config.exception

class ForbiddenException(
    message: String = "You do not have permission to perform this action.",
) : RuntimeException(message)