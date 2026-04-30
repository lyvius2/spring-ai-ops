package com.walter.spring.ai.ops.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path

class RepositoryPropertiesTest {

    @Test
    @DisplayName("repository 설정이 ConfigurationProperties로 바인딩된다")
    fun givenRepositoryProperties_whenBind_thenCreatesRepositoryProperties() {
        // given
        val environment = MockEnvironment()
            .withProperty("repository.stored", "true")
            .withProperty("repository.local-path", "./data/repository")

        // when
        val properties = Binder.get(environment)
            .bind("repository", RepositoryProperties::class.java)
            .get()

        // then
        assertThat(properties.stored).isTrue()
        assertThat(properties.localPath).isEqualTo("./data/repository")
        assertThat(properties.isPersistentStorageUsable()).isTrue()
        assertThat(properties.persistentStorageRoot()).isNotNull()
    }

    @Test
    @DisplayName("stored가 false이면 localPath가 있어도 persistent storage를 사용하지 않는다")
    fun givenStoredFalse_whenCheckUsable_thenReturnsFalse() {
        // given
        val properties = RepositoryProperties(stored = false, localPath = "./data/repository")

        // when
        val usable = properties.isPersistentStorageUsable()
        val storageRoot = properties.persistentStorageRoot()

        // then
        assertThat(usable).isFalse()
        assertThat(storageRoot).isNull()
    }

    @Test
    @DisplayName("localPath가 비어 있으면 persistent storage를 사용하지 않는다")
    fun givenBlankLocalPath_whenCheckUsable_thenReturnsFalse() {
        // given
        val properties = RepositoryProperties(stored = true, localPath = " ")

        // when
        val usable = properties.isPersistentStorageUsable()
        val storageRoot = properties.persistentStorageRoot()

        // then
        assertThat(usable).isFalse()
        assertThat(storageRoot).isNull()
    }

    @Test
    @DisplayName("localPath가 잘못된 path이면 persistent storage를 사용하지 않는다")
    fun givenInvalidLocalPath_whenCheckUsable_thenReturnsFalse() {
        // given
        val properties = RepositoryProperties(stored = true, localPath = "bad\u0000path")

        // when
        val usable = properties.isPersistentStorageUsable()
        val storageRoot = properties.persistentStorageRoot()

        // then
        assertThat(usable).isFalse()
        assertThat(storageRoot).isNull()
    }

    @Test
    @DisplayName("같은 appName과 gitUrl이면 같은 persistent repository path를 반환한다")
    fun givenSameApplicationAndGitUrl_whenResolvePersistentRepositoryPath_thenReturnsSamePath(@TempDir tempDir: Path) {
        // given
        val properties = RepositoryProperties(stored = true, localPath = tempDir.toString())

        // when
        val firstPath = properties.resolvePersistentRepositoryPath("My Service", "https://github.com/example/repo.git")
        val secondPath = properties.resolvePersistentRepositoryPath("My Service", "https://github.com/example/repo.git")

        // then
        assertThat(firstPath).isEqualTo(secondPath)
        assertThat(firstPath).isNotNull()
        assertThat(firstPath!!.startsWith(tempDir.toAbsolutePath().normalize())).isTrue()
    }

    @Test
    @DisplayName("같은 appName이라도 gitUrl이 다르면 다른 persistent repository path를 반환한다")
    fun givenDifferentGitUrls_whenResolvePersistentRepositoryPath_thenReturnsDifferentPaths(@TempDir tempDir: Path) {
        // given
        val properties = RepositoryProperties(stored = true, localPath = tempDir.toString())

        // when
        val githubPath = properties.resolvePersistentRepositoryPath("my-service", "https://github.com/example/repo.git")
        val gitlabPath = properties.resolvePersistentRepositoryPath("my-service", "https://gitlab.com/example/repo.git")

        // then
        assertThat(githubPath).isNotEqualTo(gitlabPath)
        assertThat(githubPath!!.fileName.toString()).startsWith("my-service-")
        assertThat(gitlabPath!!.fileName.toString()).startsWith("my-service-")
    }

    @Test
    @DisplayName("appName에 path traversal 문자가 있어도 localPath 밖으로 벗어나지 않는다")
    fun givenPathTraversalApplicationName_whenResolvePersistentRepositoryPath_thenReturnsSafeChildPath(@TempDir tempDir: Path) {
        // given
        val properties = RepositoryProperties(stored = true, localPath = tempDir.toString())

        // when
        val repositoryPath = properties.resolvePersistentRepositoryPath("../../my service", "https://github.com/example/repo.git")

        // then
        assertThat(repositoryPath).isNotNull()
        assertThat(repositoryPath!!.startsWith(tempDir.toAbsolutePath().normalize())).isTrue()
        assertThat(repositoryPath.fileName.toString()).doesNotContain("..")
        assertThat(properties.isSafePersistentRepositoryPath(repositoryPath)).isTrue()
    }

    @Test
    @DisplayName("localPath 자신은 삭제 가능한 repository path로 간주하지 않는다")
    fun givenStorageRootPath_whenCheckSafety_thenReturnsFalse(@TempDir tempDir: Path) {
        // given
        val properties = RepositoryProperties(stored = true, localPath = tempDir.toString())

        // when
        val safe = properties.isSafePersistentRepositoryPath(tempDir)

        // then
        assertThat(safe).isFalse()
    }

    @Test
    @DisplayName("localPath 밖의 경로는 안전한 persistent repository path로 간주하지 않는다")
    fun givenPathOutsideStorageRoot_whenCheckSafety_thenReturnsFalse(@TempDir tempDir: Path) {
        // given
        val properties = RepositoryProperties(stored = true, localPath = tempDir.resolve("repositories").toString())
        val outsidePath = tempDir.resolve("outside-repository")

        // when
        val safe = properties.isSafePersistentRepositoryPath(outsidePath)

        // then
        assertThat(safe).isFalse()
    }

    @Test
    @DisplayName("존재하는 localPath 하위 경로만 existing safe path로 판단한다")
    fun givenExistingChildPath_whenCheckExistingSafety_thenReturnsTrue(@TempDir tempDir: Path) {
        // given
        val properties = RepositoryProperties(stored = true, localPath = tempDir.toString())
        val repositoryPath = properties.resolvePersistentRepositoryPath("my-service", "https://github.com/example/repo.git")!!
        Files.createDirectories(repositoryPath)

        // when
        val existingSafe = properties.isExistingSafePersistentRepositoryPath(repositoryPath)

        // then
        assertThat(existingSafe).isTrue()
    }
}
