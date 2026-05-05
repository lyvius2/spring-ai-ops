package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.AdminListResponse
import com.walter.spring.ai.ops.controller.dto.ChangePasswordRequest
import com.walter.spring.ai.ops.controller.dto.ChangePasswordResponse
import com.walter.spring.ai.ops.controller.dto.CreateAdminRequest
import com.walter.spring.ai.ops.controller.dto.CreateAdminResponse
import com.walter.spring.ai.ops.controller.dto.LoginRequest
import com.walter.spring.ai.ops.controller.dto.LoginResponse
import com.walter.spring.ai.ops.controller.dto.RemoveAdminsRequest
import com.walter.spring.ai.ops.controller.dto.RemoveAdminsResponse
import com.walter.spring.ai.ops.service.AdminService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
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
        return LoginResponse.loginSuccess(adminService.createAuthenticatedSession(request.username, httpRequest))
    }

    @PostMapping("/logout")
    fun logout(httpRequest: HttpServletRequest): LoginResponse {
        adminService.invalidateSession(httpRequest)
        return LoginResponse.logoutSuccess()
    }

    @PostMapping("/password")
    fun changePassword(@RequestBody request: ChangePasswordRequest, httpRequest: HttpServletRequest): ChangePasswordResponse {
        val username = SecurityContextHolder.getContext().authentication?.name
            ?: return ChangePasswordResponse.changePasswordFailure(IllegalStateException("Not authenticated."))
        return try {
            adminService.changePassword(username, request.currentPassword, request.newPassword, request.confirmPassword)
            httpRequest.changeSessionId()
            ChangePasswordResponse.changePasswordSuccess()
        } catch (e: IllegalArgumentException) {
            ChangePasswordResponse.changePasswordFailure(e)
        } catch (e: IllegalStateException) {
            ChangePasswordResponse.changePasswordFailure(e)
        }
    }

    @PostMapping("/admin")
    fun createAdmin(@RequestBody request: CreateAdminRequest): CreateAdminResponse {
        return try {
            adminService.createAdmin(request.username, request.password, request.confirmPassword)
            CreateAdminResponse.success()
        } catch (e: IllegalArgumentException) {
            CreateAdminResponse.failure(e)
        }
    }

    @GetMapping("/admins")
    fun listAdmins(): AdminListResponse {
        return AdminListResponse(adminService.getAdminDetails())
    }

    @DeleteMapping("/admins")
    fun removeAdmins(@RequestBody request: RemoveAdminsRequest): RemoveAdminsResponse {
        return try {
            adminService.removeAdmins(request.usernames)
            RemoveAdminsResponse.success()
        } catch (e: IllegalArgumentException) {
            RemoveAdminsResponse.failure(e)
        }
    }
}