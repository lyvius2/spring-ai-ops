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
        val path = request.requestURI
        return PROTECTED_ROUTES.any { (m, p, exact) ->
            method == m && if (exact) path == p else path.startsWith(p)
        }
    }

    companion object {
        private data class Route(val method: String, val path: String, val exact: Boolean = true)
        private val PROTECTED_ROUTES = listOf(
            Route("POST", "/api/apps"),
            Route("PUT", "/api/apps/", exact = false),
            Route("DELETE", "/api/apps/", exact = false),
            Route("POST", "/api/code-risk"),
            Route("POST", "/api/auth/admin"),
            Route("GET", "/api/auth/admins"),
            Route("DELETE", "/api/auth/admins"),
            Route("POST", "/api/loki/config"),
            Route("POST", "/api/prometheus/config"),
            Route("POST", "/api/github/config"),
            Route("POST", "/api/llm/config"),
            Route("POST", "/api/llm/select-provider"),
        )
    }
}