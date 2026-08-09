package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.config.exception.ForbiddenException
import com.walter.spring.ai.ops.config.exception.InvalidPullRequestException
import com.walter.spring.ai.ops.config.exception.UnauthorizedException
import com.walter.spring.ai.ops.controller.dto.ErrorResponse
import com.walter.spring.ai.ops.controller.dto.GithubPullRequestResponse
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

@ControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(InvalidPullRequestException::class)
    fun handleInvalidPullRequest(ex: InvalidPullRequestException, request: HttpServletRequest): ResponseEntity<Any> {
        log.error(ex.message)
        return ResponseEntity
            .status(HttpStatus.NO_CONTENT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(GithubPullRequestResponse.invalid())
    }

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException, request: HttpServletRequest): ResponseEntity<Any> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(HttpStatus.UNAUTHORIZED.value(), ex.message ?: "Unauthorized", request.requestURI))
    }

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(ex: ForbiddenException, request: HttpServletRequest): ResponseEntity<Any> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(HttpStatus.FORBIDDEN.value(), ex.message ?: "Forbidden", request.requestURI))
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
                .body(
                    ErrorResponse(
                        HttpStatus.NOT_FOUND.value(),
                        "The requested API endpoint does not exist: $path",
                        path
                    )
                )
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