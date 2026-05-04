package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.config.exception.UnauthorizedException
import com.walter.spring.ai.ops.controller.dto.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

@ControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException, request: HttpServletRequest): ResponseEntity<Any> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(HttpStatus.UNAUTHORIZED.value(), ex.message ?: "Unauthorized", request.requestURI))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(ex: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<Any> {
        val path = request.requestURI
        if (isStaticResource(path)) {
            throw ex
        }
        if (path.startsWith("/api/")) {
            return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse(HttpStatus.NOT_FOUND.value(), "The requested API endpoint does not exist: $path", path))
        }
        return ResponseEntity
            .status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, "/")
            .build()
    }

    private fun isStaticResource(path: String): Boolean {
        val lastSegment = path.substringAfterLast("/")
        return lastSegment.contains(".")
    }
}
