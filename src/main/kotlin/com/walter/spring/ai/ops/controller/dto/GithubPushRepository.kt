package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPushRepository(
    val name: String = "",
    val owner: GithubPushOwner = GithubPushOwner(),
    @JsonProperty("html_url") val htmlUrl: String = "",
)
