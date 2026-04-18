package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitlabFile(
    @JsonProperty("old_path") val oldPath: String = "",
    @JsonProperty("new_path") val newPath: String = "",
    @JsonProperty("new_file") val newFile: Boolean = false,
    @JsonProperty("renamed_file") val renamedFile: Boolean = false,
    @JsonProperty("deleted_file") val deletedFile: Boolean = false,
    val diff: String = "",
)

