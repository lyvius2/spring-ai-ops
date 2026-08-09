package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitlabMergeRequestNoteResponse(
    val id: Long = 0L,
    val body: String = "",
    @JsonProperty("created_at") val createdAt: String = "",
    @JsonProperty("updated_at") val updatedAt: String = "",
    val system: Boolean = false,
    @JsonProperty("noteable_id") val noteableId: Long = 0L,
    @JsonProperty("noteable_type") val noteableType: String = "",
    @JsonProperty("noteable_iid") val noteableIid: Long = 0L,
    val resolvable: Boolean = false,
    val confidential: Boolean = false,
    val errorMessage: String? = null,
)