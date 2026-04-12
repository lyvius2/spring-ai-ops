package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubCompareResult(
    val files: List<GithubFile> = emptyList(),
    val errorMessage: String = "",
)
