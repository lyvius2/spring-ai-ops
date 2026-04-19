package com.walter.spring.ai.ops.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

class RepositoryServiceTest {

    private lateinit var service: RepositoryService

    /** Local bare git repository used as the remote origin */
    private lateinit var remoteRepoDir: Path

    /** All temp directories created during tests — cleaned up in @AfterEach */
    private val tempDirs = mutableListOf<Path>()

    @BeforeEach
    fun setUp() {
        service = RepositoryService()

        // Create a local bare repository with one commit on 'main' and one on 'feature'
        remoteRepoDir = Files.createTempDirectory("test-remote-repo")
        tempDirs.add(remoteRepoDir)

        Git.init().setDirectory(remoteRepoDir.toFile()).call().use { git ->
            remoteRepoDir.resolve("README.md").toFile().writeText("# Test Repository")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial commit")
                .setAuthor("Test", "test@example.com")
                .call()

            // Create a 'feature' branch
            git.branchCreate().setName("feature").call()
        }
    }

    @AfterEach
    @OptIn(ExperimentalPathApi::class)
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    // ── cloneRepository ───────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 gitUrl로 clone 시 임시 디렉터리 경로를 반환한다")
    fun givenValidGitUrl_whenCloneRepository_thenReturnsValidPath() {
        // given
        val gitUrl = remoteRepoDir.toUri().toString()

        // when
        val result = service.cloneRepository("test-app", gitUrl)
        tempDirs.add(result)

        // then
        assertThat(result).isNotNull()
        assertThat(result.toFile()).exists().isDirectory()
    }

    @Test
    @DisplayName("clone된 디렉터리에는 .git 메타데이터가 존재한다")
    fun givenValidGitUrl_whenCloneRepository_thenClonedDirContainsGitMetadata() {
        // given
        val gitUrl = remoteRepoDir.toUri().toString()

        // when
        val result = service.cloneRepository("test-app", gitUrl)
        tempDirs.add(result)

        // then
        assertThat(result.resolve(".git").toFile()).exists().isDirectory()
    }

    @Test
    @DisplayName("clone된 디렉터리 이름에 appName이 포함된다")
    fun givenAppName_whenCloneRepository_thenTempDirNameContainsAppName() {
        // given
        val appName = "my-service"
        val gitUrl = remoteRepoDir.toUri().toString()

        // when
        val result = service.cloneRepository(appName, gitUrl)
        tempDirs.add(result)

        // then
        assertThat(result.fileName.toString()).contains(appName)
    }

    @Test
    @DisplayName("branch를 지정하면 해당 브랜치로 clone된다")
    fun givenBranchName_whenCloneRepository_thenClonesSpecifiedBranch() {
        // given
        val gitUrl = remoteRepoDir.toUri().toString()

        // when
        val result = service.cloneRepository("test-app", gitUrl, branch = "feature")
        tempDirs.add(result)

        // then
        Git.open(result.toFile()).use { git ->
            assertThat(git.repository.branch).isEqualTo("feature")
        }
    }

    @Test
    @DisplayName("branch를 지정하지 않으면 기본 브랜치로 clone된다")
    fun givenNoBranch_whenCloneRepository_thenClonesDefaultBranch() {
        // given
        val gitUrl = remoteRepoDir.toUri().toString()

        // when
        val result = service.cloneRepository("test-app", gitUrl)
        tempDirs.add(result)

        // then — default branch is 'master' or 'main' depending on JGit default
        Git.open(result.toFile()).use { git ->
            assertThat(git.repository.branch).isNotBlank()
        }
    }

    @Test
    @DisplayName("잘못된 gitUrl로 clone 시 예외가 발생한다")
    fun givenInvalidGitUrl_whenCloneRepository_thenThrowsException() {
        // given
        val invalidUrl = "https://invalid.example.invalid/no-such-repo.git"

        // when & then
        assertThatThrownBy {
            service.cloneRepository("test-app", invalidUrl)
        }.isInstanceOf(Exception::class.java)
    }
}
