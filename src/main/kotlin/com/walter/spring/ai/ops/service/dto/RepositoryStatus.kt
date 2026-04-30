package com.walter.spring.ai.ops.service.dto

import com.walter.spring.ai.ops.code.RepositoryCloneStatus
import java.nio.file.Path
import java.time.LocalDateTime

data class RepositoryStatus(
    val applicationName: String,
    val cloneStatus: RepositoryCloneStatus = RepositoryCloneStatus.NOT_STARTED,
    val localPath: String? = null,
    val lastSyncedAt: LocalDateTime? = null,
    val lastError: String? = null,
) {
    companion object {
        fun running(appName: String, path: Path) = RepositoryStatus(appName, RepositoryCloneStatus.RUNNING, path.toString())
        fun success(appName: String, path: Path) = RepositoryStatus(appName, RepositoryCloneStatus.SUCCESS, path.toString(), LocalDateTime.now())
        fun failed(appName: String, path: Path, e: Throwable) = RepositoryStatus(
            applicationName = appName,
            cloneStatus = RepositoryCloneStatus.FAILED,
            localPath = path.toString(),
            lastError = e.message ?: e::class.simpleName
        )
    }
}
