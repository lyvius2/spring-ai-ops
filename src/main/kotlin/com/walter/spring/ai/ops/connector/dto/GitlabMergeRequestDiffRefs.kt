package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitlabMergeRequestDiffRefs(
    @JsonProperty("base_sha") val baseSha: String = "",
    @JsonProperty("head_sha") val headSha: String = "",
    @JsonProperty("start_sha") val startSha: String = "",
)