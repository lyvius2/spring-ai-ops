package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubIssueCommentResponse(
    val id: Long = 0L,
    @JsonProperty("node_id") val nodeId: String = "",
    val url: String = "",
    @JsonProperty("html_url") val htmlUrl: String = "",
    @JsonProperty("issue_url") val issueUrl: String = "",
    val body: String = "",
    @JsonProperty("created_at") val createdAt: String = "",
    @JsonProperty("updated_at") val updatedAt: String = "",
    @JsonProperty("author_association") val authorAssociation: String = "",
    val errorMessage: String? = null,
)