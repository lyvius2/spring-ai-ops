package com.walter.spring.ai.ops.service.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.walter.spring.ai.ops.controller.dto.AppUpdateRequest

data class AppConfig @JsonCreator constructor(
    @JsonProperty("gitUrl") val gitUrl: String?,
    @JsonProperty("deployBranch") val deployBranch: String?,
    @JsonProperty("isSend") val isSend: Boolean = false,
    @JsonProperty("slackChannel") val slackChannel: String? = null,
) {
    constructor(appUpdateRequest: AppUpdateRequest) : this(appUpdateRequest.gitUrl, appUpdateRequest.deployBranch, appUpdateRequest.isSend, appUpdateRequest.slackChannel)

    @JsonIgnore
    fun isValidConfig(): Boolean = !gitUrl.isNullOrBlank() && !deployBranch.isNullOrBlank()
}
