package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.AppAddRequest
import com.walter.spring.ai.ops.controller.dto.AppAddResponse
import com.walter.spring.ai.ops.controller.dto.AppGitResponse
import com.walter.spring.ai.ops.controller.dto.AppRemoveResponse
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest
import com.walter.spring.ai.ops.controller.dto.AppUpdateResponse
import com.walter.spring.ai.ops.service.ApplicationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Application", description = "Application registry — register and manage monitored application names")
@RestController
@RequestMapping("/api/apps")
class ApplicationController(
    private val applicationService: ApplicationService
) {
    @Operation(summary = "List all registered applications")
    @GetMapping
    fun getApps(): List<String> {
        return applicationService.getApps()
    }

    @Operation(summary = "Get application config (name, git URL)")
    @GetMapping("/{name}")
    fun getApp(@Parameter(description = "Application name", required = true) @PathVariable name: String): AppGitResponse {
        return AppGitResponse(name, applicationService.getGitUrl(name))
    }

    @Operation(
        summary = "Register a new application",
        description = "Adds the application name to the registry. Idempotent — re-registering an existing name is silently ignored."
    )
    @PostMapping
    fun addApp(@RequestBody request: AppAddRequest): AppAddResponse {
        return try {
            applicationService.addApp(request.name, request.gitUrl)
            AppAddResponse.success()
        } catch (e: Exception) {
            AppAddResponse.failure(e)
        }
    }

    @Operation(summary = "Update application name and/or git URL")
    @PutMapping("/{name}")
    fun updateApp(@Parameter(description = "Current application name", required = true) @PathVariable name: String, @RequestBody request: AppUpdateRequest): AppUpdateResponse {
        return try {
            applicationService.updateApp(name, request.name, request.gitUrl)
            AppUpdateResponse.success()
        } catch (e: Exception) {
            AppUpdateResponse.failure(e)
        }
    }

    @Operation(summary = "Remove an application from the registry")
    @DeleteMapping("/{name}")
    fun removeApp(@Parameter(description = "Application name to remove", required = true) @PathVariable name: String): AppRemoveResponse {
        applicationService.removeApp(name)
        return AppRemoveResponse.success()
    }
}
