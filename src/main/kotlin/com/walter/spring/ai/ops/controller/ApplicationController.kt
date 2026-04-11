package com.walter.spring.ai.ops.controller

import com.walter.spring.ai.ops.controller.dto.AppAddRequest
import com.walter.spring.ai.ops.controller.dto.AppAddResponse
import com.walter.spring.ai.ops.controller.dto.AppRemoveResponse
import com.walter.spring.ai.ops.service.AppService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/apps")
class AppController(
    private val appService: AppService
) {

    @GetMapping
    fun getApps(): List<String> = appService.getApps()

    @PostMapping
    fun addApp(@RequestBody request: AppAddRequest): AppAddResponse {
        return try {
            appService.addApp(request.name)
            AppAddResponse.success()
        } catch (e: IllegalArgumentException) {
            AppAddResponse.failure(e)
        }
    }

    @DeleteMapping("/{name}")
    fun removeApp(@PathVariable name: String): AppRemoveResponse {
        appService.removeApp(name)
        return AppRemoveResponse.success()
    }
}
