package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitlabMergeRequestDetail(
    val iid: Int = 0,
    @JsonProperty("diff_refs") val diffRefs: GitlabMergeRequestDiffRefs? = null,
    val errorMessage: String? = null,
)