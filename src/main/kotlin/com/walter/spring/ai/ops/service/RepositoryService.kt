package com.walter.spring.ai.ops.service

import org.eclipse.jgit.api.Git
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class RepositoryService {
    fun cloneRepository(appName: String, gitUrl: String, branch: String = ""): Path {
        val tempDir = Files.createTempDirectory("repository-scan-$appName")
        Git.cloneRepository()
            .setURI(gitUrl)
            .setDirectory(tempDir.toFile())
            .setCloneAllBranches(false)
            .apply { if (branch.isNotEmpty()) setBranch(branch) }
            .setDepth(1)
            .call()
            .use { /* Cloned successfully, tempDir contains the repository */ }
        return tempDir
    }
}