package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitlabDiscussionNote(
    val id: Long = 0L,
    val body: String = "",
    @JsonProperty("created_at") val createdAt: String = "",
    @JsonProperty("updated_at") val updatedAt: String = "",
    val system: Boolean = false,
    val resolvable: Boolean = false,
)