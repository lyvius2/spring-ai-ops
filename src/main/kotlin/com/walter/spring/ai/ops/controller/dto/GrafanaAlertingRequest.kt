package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GrafanaAlertingRequest(
    val receiver: String,
    val status: String,
    val orgId: Long,
    val alerts: List<GrafanaAlert>,
    val groupLabels: Map<String, String>,
    val commonLabels: Map<String, String>,
    val commonAnnotations: Map<String, String>,
    val externalURL: String,
    val version: String,
    val groupKey: String,
    val truncatedAlerts: Int,
    val title: String,
    val state: String,
    val message: String,
) {
    fun isFiring(): Boolean = status == "firing"
    fun isResolved(): Boolean = status == "resolved"
}
