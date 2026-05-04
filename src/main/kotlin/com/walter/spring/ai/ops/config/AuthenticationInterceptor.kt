package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.config.exception.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class AuthenticationInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (!requiresAuthentication(request)) {
            return true
        }
        val auth = SecurityContextHolder.getContext().authentication
        val authenticated = auth != null && auth.isAuthenticated && auth !is AnonymousAuthenticationToken
        if (!authenticated) {
            throw UnauthorizedException()
        }
        return true
    }

    private fun requiresAuthentication(request: HttpServletRequest): Boolean {
        val method = request.method
        val path   = request.requestURI
        return (method == "POST"   && path == "/api/apps")
            || (method == "PUT"    && path.startsWith("/api/apps/"))
            || (method == "DELETE" && path.startsWith("/api/apps/"))
            || (method == "POST"   && path == "/api/code-risk")
    }
}