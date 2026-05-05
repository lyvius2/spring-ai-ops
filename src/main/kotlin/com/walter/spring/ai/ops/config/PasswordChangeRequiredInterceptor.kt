package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.config.exception.ForbiddenException
import com.walter.spring.ai.ops.service.AdminService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class PasswordChangeRequiredInterceptor(
    private val adminService: AdminService,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (isExemptPath(request)) return true
        val auth = SecurityContextHolder.getContext().authentication
        if (auth == null || !auth.isAuthenticated || auth is AnonymousAuthenticationToken) return true
        if (adminService.isPasswordChangeRequired(auth.name)) {
            throw ForbiddenException("Password change required. Please update your password before proceeding.")
        }
        return true
    }

    private fun isExemptPath(request: HttpServletRequest): Boolean {
        val method = request.method
        val path = request.requestURI
        return (method == "POST" && path == "/api/auth/password")
            || (method == "POST" && path == "/api/auth/logout")
    }
}