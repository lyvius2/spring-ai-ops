package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitlabApiCommit(
    val id: String = "",
    @JsonProperty("short_id") val shortId: String = "",
    val title: String = "",
    val message: String = "",
    @JsonProperty("author_name") val authorName: String = "",
    @JsonProperty("authored_date") val authoredDate: String = "",
    @JsonProperty("web_url") val webUrl: String = "",
    val errorMessage: String? = null,
)
