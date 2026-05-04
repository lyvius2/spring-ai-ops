package com.walter.spring.ai.ops.config.aspect

import com.walter.spring.ai.ops.config.exception.ForbiddenException
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Aspect
@Component
class AdminOnlyAspect {

    @Before("@annotation(com.walter.spring.ai.ops.config.annotation.AdminOnly)")
    fun checkAdminOnly() {
        val username = SecurityContextHolder.getContext().authentication?.name
        if (username != "admin") {
            throw ForbiddenException("Only the 'admin' account is authorized to perform this action.")
        }
    }
}