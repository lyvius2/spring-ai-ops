package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.ChangePasswordRequest
import com.walter.spring.ai.ops.controller.dto.ChangePasswordResponse
import com.walter.spring.ai.ops.controller.dto.LoginRequest
import com.walter.spring.ai.ops.controller.dto.LoginResponse
import com.walter.spring.ai.ops.service.AdminService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val adminService: AdminService,
) {
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest, httpRequest: HttpServletRequest): LoginResponse {
        if (!adminService.authenticate(request.username, request.password)) {
            return LoginResponse.loginFailure()
        }
        adminService.createAuthenticatedSession(request.username, httpRequest)
        return LoginResponse.loginSuccess()
    }

    @PostMapping("/logout")
    fun logout(httpRequest: HttpServletRequest): LoginResponse {
        adminService.invalidateSession(httpRequest)
        return LoginResponse.logoutSuccess()
    }

    @PostMapping("/password")
    fun changePassword(@RequestBody request: ChangePasswordRequest): ChangePasswordResponse {
        return try {
            adminService.changePassword(request.username, request.currentPassword, request.newPassword, request.confirmPassword)
            ChangePasswordResponse.changePasswordSuccess()
        } catch (e: IllegalArgumentException) {
            ChangePasswordResponse.changePasswordFailure(e)
        } catch (e: IllegalStateException) {
            ChangePasswordResponse.changePasswordFailure(e)
        }
    }
}