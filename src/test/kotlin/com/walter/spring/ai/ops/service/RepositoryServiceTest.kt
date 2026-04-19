package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_CODE_RISK_PREFIX
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

@ExtendWith(MockitoExtension::class)
class RepositoryServiceTest {

    @Mock private lateinit var redisTemplate: StringRedisTemplate
    @Mock private lateinit var zSetOps: ZSetOperations<String, String>

    private lateinit var service: RepositoryService

    /** Local bare git repository used as the remote origin */
    private lateinit var remoteRepoDir: Path

    /** All temp directories created during tests — cleaned up in @AfterEach */
    private val tempDirs = mutableListOf<Path>()

    @BeforeEach
    fun setUp() {
        service = RepositoryService(
            redisTemplate = redisTemplate,
            objectMapper = ObjectMapper(),
            retentionHours = 120L,
            maximumViewCount = 5L,
        )

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

    // ── collectSourceFiles ────────────────────────────────────────────────────

    @Test
    @DisplayName("허용된 확장자의 파일만 수집된다")
    fun givenRepositoryWithMixedFiles_whenCollectSourceFiles_thenReturnsOnlyAllowedExtensions() {
        // given
        val root = Files.createTempDirectory("collect-test").also { tempDirs.add(it) }
        root.resolve("Main.kt").toFile().writeText("fun main() {}")
        root.resolve("app.js").toFile().writeText("console.log('hi')")
        root.resolve("image.png").toFile().writeBytes(byteArrayOf(0x89.toByte(), 0x50))
        root.resolve("binary.exe").toFile().writeBytes(byteArrayOf(0x4D, 0x5A))

        // when
        val result = service.collectSourceFiles(root)

        // then
        assertThat(result.map { it.fileName.toString() })
            .containsExactlyInAnyOrder("Main.kt", "app.js")
    }

    @Test
    @DisplayName("제외 디렉터리 내 파일은 수집되지 않는다")
    fun givenRepositoryWithExcludedDirs_whenCollectSourceFiles_thenExcludesDirContents() {
        // given
        val root = Files.createTempDirectory("collect-exclude-test").also { tempDirs.add(it) }
        root.resolve("src").toFile().mkdirs()
        root.resolve("src/Service.kt").toFile().writeText("class Service")
        root.resolve("build").toFile().mkdirs()
        root.resolve("build/Output.kt").toFile().writeText("class Output")
        root.resolve("node_modules").toFile().mkdirs()
        root.resolve("node_modules/lib.js").toFile().writeText("module.exports = {}")

        // when
        val result = service.collectSourceFiles(root)

        // then
        assertThat(result.map { it.fileName.toString() }).containsExactly("Service.kt")
        assertThat(result.map { it.fileName.toString() })
            .doesNotContain("Output.kt", "lib.js")
    }

    @Test
    @DisplayName("300KB 초과 파일은 수집에서 제외된다")
    fun givenOversizedFile_whenCollectSourceFiles_thenExcludesLargeFile() {
        // given
        val root = Files.createTempDirectory("collect-size-test").also { tempDirs.add(it) }
        root.resolve("small.kt").toFile().writeText("class Small")
        root.resolve("large.kt").toFile().writeBytes(ByteArray(300_001) { 'a'.code.toByte() })

        // when
        val result = service.collectSourceFiles(root)

        // then
        assertThat(result.map { it.fileName.toString() }).containsExactly("small.kt")
    }

    @Test
    @DisplayName("파일이 없는 디렉터리에서 수집 시 빈 목록을 반환한다")
    fun givenEmptyRepository_whenCollectSourceFiles_thenReturnsEmptyList() {
        // given
        val root = Files.createTempDirectory("collect-empty-test").also { tempDirs.add(it) }

        // when
        val result = service.collectSourceFiles(root)

        // then
        assertThat(result).isEmpty()
    }

    // ── buildBundle ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("buildBundle은 모든 파일 내용을 하나의 문자열로 묶어 반환한다")
    fun givenSourceFiles_whenBuildBundle_thenContainsAllFileContents() {
        // given
        val root = Files.createTempDirectory("bundle-test").also { tempDirs.add(it) }
        root.resolve("A.kt").toFile().writeText("class A")
        root.resolve("B.kt").toFile().writeText("class B")
        val files = listOf(root.resolve("A.kt"), root.resolve("B.kt"))

        // when
        val result = service.buildBundle(root, files)

        // then
        assertThat(result).contains("class A")
        assertThat(result).contains("class B")
    }

    @Test
    @DisplayName("buildBundle 결과에 각 파일의 상대 경로가 포함된다")
    fun givenSourceFiles_whenBuildBundle_thenContainsRelativePaths() {
        // given
        val root = Files.createTempDirectory("bundle-path-test").also { tempDirs.add(it) }
        root.resolve("sub").toFile().mkdirs()
        root.resolve("sub/Service.kt").toFile().writeText("class Service")
        val files = listOf(root.resolve("sub/Service.kt"))

        // when
        val result = service.buildBundle(root, files)

        // then
        assertThat(result).contains("sub/Service.kt")
    }

    @Test
    @DisplayName("buildBundle에 파일 목록이 비어있으면 헤더만 포함된 문자열을 반환한다")
    fun givenEmptyFileList_whenBuildBundle_thenReturnsHeaderOnly() {
        // given
        val root = Files.createTempDirectory("bundle-empty-test").also { tempDirs.add(it) }

        // when
        val result = service.buildBundle(root, emptyList())

        // then
        assertThat(result).contains("# Repository source code bundle")
        assertThat(result).doesNotContain("## File:")
    }

    // ── createChunks ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("서로 다른 상위 디렉터리의 파일들은 각각 별도 청크로 분리된다")
    fun givenFilesInDifferentDirectories_whenCreateChunks_thenEachDirectoryBecomesChunk() {
        // given
        val root = Files.createTempDirectory("chunk-test").also { tempDirs.add(it) }
        root.resolve("service").toFile().mkdirs()
        root.resolve("controller").toFile().mkdirs()
        root.resolve("service/UserService.kt").toFile().writeText("class UserService")
        root.resolve("controller/UserController.kt").toFile().writeText("class UserController")
        val files = listOf(root.resolve("service/UserService.kt"), root.resolve("controller/UserController.kt"))

        // when
        val result = service.createChunks(root, files)

        // then
        assertThat(result).hasSize(2)
        assertThat(result.map { it.label }).containsExactlyInAnyOrder("service", "controller")
    }

    @Test
    @DisplayName("같은 디렉터리의 파일들은 하나의 청크로 묶인다")
    fun givenFilesInSameDirectory_whenCreateChunks_thenMergedIntoOneChunk() {
        // given
        val root = Files.createTempDirectory("chunk-merge-test").also { tempDirs.add(it) }
        root.resolve("service").toFile().mkdirs()
        root.resolve("service/UserService.kt").toFile().writeText("class UserService")
        root.resolve("service/OrderService.kt").toFile().writeText("class OrderService")
        val files = listOf(root.resolve("service/UserService.kt"), root.resolve("service/OrderService.kt"))

        // when
        val result = service.createChunks(root, files)

        // then
        assertThat(result).hasSize(1)
        assertThat(result.first().label).isEqualTo("service")
        assertThat(result.first().bundle).contains("class UserService")
        assertThat(result.first().bundle).contains("class OrderService")
    }

    @Test
    @DisplayName("루트 바로 아래 파일은 'root' 레이블 청크로 분류된다")
    fun givenFileAtRepositoryRoot_whenCreateChunks_thenLabelIsRoot() {
        // given
        val root = Files.createTempDirectory("chunk-root-test").also { tempDirs.add(it) }
        root.resolve("README.md").toFile().writeText("# Project")
        val files = listOf(root.resolve("README.md"))

        // when
        val result = service.createChunks(root, files)

        // then
        assertThat(result).hasSize(1)
        assertThat(result.first().label).isEqualTo("root")
    }

    @Test
    @DisplayName("파일 목록이 비어있으면 빈 청크 목록을 반환한다")
    fun givenEmptyFileList_whenCreateChunks_thenReturnsEmptyList() {
        // given
        val root = Files.createTempDirectory("chunk-empty-test").also { tempDirs.add(it) }

        // when
        val result = service.createChunks(root, emptyList())

        // then
        assertThat(result).isEmpty()
    }

    // ── scanAllAtOnce ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 gitUrl로 scanAllAtOnce 시 파일 내용이 포함된 번들 문자열을 반환한다")
    fun givenValidGitUrl_whenScanAllAtOnce_thenReturnsBundleContainingFileContents() {
        // given
        val gitUrl = remoteRepoDir.toUri().toString()

        // when
        val result = service.scanAllAtOnce("test-app", gitUrl)

        // then
        assertThat(result).contains("# Repository source code bundle")
        assertThat(result).contains("README.md")
        assertThat(result).contains("# Test Repository")
    }

    @Test
    @DisplayName("잘못된 gitUrl로 scanAllAtOnce 시 예외가 발생한다")
    fun givenInvalidGitUrl_whenScanAllAtOnce_thenThrowsException() {
        // given
        val invalidUrl = "https://invalid.example.invalid/no-such-repo.git"

        // when & then
        assertThatThrownBy {
            service.scanAllAtOnce("test-app", invalidUrl)
        }.isInstanceOf(Exception::class.java)
    }

    // ── saveAnalyzedResult ────────────────────────────────────────────────────

    @Test
    @DisplayName("saveAnalyzedResult는 CodeRiskRecord를 반환하고 Redis에 저장한다")
    fun givenValidParams_whenSaveAnalyzedResult_thenReturnsRecordAndSavesToRedis() {
        // given
        `when`(redisTemplate.opsForZSet()).thenReturn(zSetOps)

        // when
        val record = service.saveAnalyzedResult("my-app", "https://github.com/owner/repo.git", "main", "## Analysis\nNo issues found.")

        // then
        assertThat(record.application()).isEqualTo("my-app")
        assertThat(record.githubUrl()).isEqualTo("https://github.com/owner/repo.git")
        assertThat(record.branch()).isEqualTo("main")
        assertThat(record.analyzedResult()).isEqualTo("## Analysis\nNo issues found.")
        assertThat(record.analyzedAt()).isNotNull()
    }

    // ── getCodeRiskRecords ────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 레코드가 있으면 getCodeRiskRecords는 목록을 반환한다")
    fun givenRecordsInRedis_whenGetCodeRiskRecords_thenReturnsList() {
        // given
        val objectMapper = ObjectMapper().findAndRegisterModules()
        val innerService = RepositoryService(redisTemplate, objectMapper, 120L, 5L)
        val json = """{"analyzedAt":"2026-04-20T10:00:00","application":"my-app","githubUrl":"https://github.com/owner/repo.git","branch":"main","analyzedResult":"ok"}"""
        `when`(redisTemplate.opsForZSet()).thenReturn(zSetOps)
        `when`(zSetOps.reverseRange("${REDIS_KEY_CODE_RISK_PREFIX}my-app", 0, -1)).thenReturn(linkedSetOf(json))

        // when
        val result = innerService.getCodeRiskRecords("my-app")

        // then
        assertThat(result).hasSize(1)
        assertThat(result.first().application()).isEqualTo("my-app")
    }

    @Test
    @DisplayName("Redis에 레코드가 없으면 getCodeRiskRecords는 빈 목록을 반환한다")
    fun givenNoRecordsInRedis_whenGetCodeRiskRecords_thenReturnsEmptyList() {
        // given
        `when`(redisTemplate.opsForZSet()).thenReturn(zSetOps)
        `when`(zSetOps.reverseRange("${REDIS_KEY_CODE_RISK_PREFIX}my-app", 0, -1)).thenReturn(emptySet())

        // when
        val result = service.getCodeRiskRecords("my-app")

        // then
        assertThat(result).isEmpty()
    }

    // ── hasCodeRiskRecords ────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 레코드가 있으면 hasCodeRiskRecords는 true를 반환한다")
    fun givenRecordsInRedis_whenHasCodeRiskRecords_thenReturnsTrue() {
        // given
        `when`(redisTemplate.opsForZSet()).thenReturn(zSetOps)
        `when`(zSetOps.zCard("${REDIS_KEY_CODE_RISK_PREFIX}my-app")).thenReturn(3L)

        // when
        val result = service.hasCodeRiskRecords("my-app")

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("Redis에 레코드가 없으면 hasCodeRiskRecords는 false를 반환한다")
    fun givenNoRecordsInRedis_whenHasCodeRiskRecords_thenReturnsFalse() {
        // given
        `when`(redisTemplate.opsForZSet()).thenReturn(zSetOps)
        `when`(zSetOps.zCard("${REDIS_KEY_CODE_RISK_PREFIX}my-app")).thenReturn(0L)

        // when
        val result = service.hasCodeRiskRecords("my-app")

        // then
        assertThat(result).isFalse()
    }
}
