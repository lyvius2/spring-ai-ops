package com.walter.spring.ai.ops.service.dto

import com.walter.spring.ai.ops.code.AlertMessageType

data class AlertMessage(
    val type: AlertMessageType,
    val applicationName: String,
    val deployBranch: String? = null,
    val exceptionMessage: String? = null,
) {
    companion object {
        fun checkoutFailed(applicationName: String, e: Exception) = AlertMessage(
            type = AlertMessageType.SOURCE_CHECKOUT_FAILED,
            applicationName = applicationName,
            exceptionMessage = e.message ?: e::class.simpleName ?: "Unknown error",
        )
        fun fallbackFailed(applicationName: String, deployBranch: String, e: Exception) = AlertMessage(
            type = AlertMessageType.INVALID_DEPLOY_BRANCH_FALLBACK,
            applicationName = applicationName,
            deployBranch = deployBranch,
            exceptionMessage = e.message ?: e::class.simpleName ?: "Unknown error",
        )
    }
}
