package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitlabDiscussionResponse(
    val id: String = "",
    @JsonProperty("individual_note") val individualNote: Boolean = false,
    val notes: List<GitlabDiscussionNote> = emptyList(),
    val errorMessage: String? = null,
)