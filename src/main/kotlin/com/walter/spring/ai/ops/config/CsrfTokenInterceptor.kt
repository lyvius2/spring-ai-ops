package com.walter.spring.ai.ops.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class CsrfTokenInterceptor(
    private val csrfTokenProvider: CsrfTokenProvider,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val token = request.getHeader("X-CSRF-Token")
        if (token.isNullOrBlank() || token != csrfTokenProvider.token) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Access denied: invalid CSRF token.")
            return false
        }
        return true
    }
}
