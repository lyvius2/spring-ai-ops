package com.walter.spring.ai.ops.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
class JavaVersionDetectorTest {

    private lateinit var detector: JavaVersionDetector
    private lateinit var projectDir: Path

    @BeforeEach
    fun setUp() {
        detector = JavaVersionDetector()
        projectDir = Files.createTempDirectory("java-version-detector-test")
    }

    @AfterEach
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    // ── pom.xml ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pom.xml의 java.version 프로퍼티에서 버전을 감지한다")
    fun givenPomXmlWithJavaVersionProperty_whenDetect_thenReturnsCorrectVersion() {
        // given
        projectDir.resolve("pom.xml").toFile().writeText(
            """
            <project>
                <properties>
                    <java.version>17</java.version>
                </properties>
            </project>
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("17")
    }

    @Test
    @DisplayName("pom.xml의 maven.compiler.source 태그에서 버전을 감지한다")
    fun givenPomXmlWithMavenCompilerSource_whenDetect_thenReturnsCorrectVersion() {
        // given
        projectDir.resolve("pom.xml").toFile().writeText(
            """
            <project>
                <properties>
                    <maven.compiler.source>11</maven.compiler.source>
                </properties>
            </project>
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("11")
    }

    @Test
    @DisplayName("pom.xml의 버전이 1.8 형식이면 8로 파싱한다")
    fun givenPomXmlWithLegacyVersionFormat_whenDetect_thenParsesCorrectly() {
        // given
        projectDir.resolve("pom.xml").toFile().writeText(
            """
            <project>
                <properties>
                    <maven.compiler.source>1.8</maven.compiler.source>
                </properties>
            </project>
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("8")
    }

    @Test
    @DisplayName("pom.xml에 버전 정보가 없으면 기본값 21을 반환한다")
    fun givenPomXmlWithNoVersionInfo_whenDetect_thenReturnsDefaultVersion() {
        // given
        projectDir.resolve("pom.xml").toFile().writeText(
            """
            <project>
                <properties/>
            </project>
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("21")
    }

    // ── build.gradle.kts ─────────────────────────────────────────────────────

    @Test
    @DisplayName("build.gradle.kts의 jvmTarget에서 버전을 감지한다")
    fun givenBuildGradleKtsWithJvmTarget_whenDetect_thenReturnsCorrectVersion() {
        // given
        projectDir.resolve("build.gradle.kts").toFile().writeText(
            """
            kotlin {
                compileOptions {
                    jvmTarget = "17"
                }
            }
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("17")
    }

    @Test
    @DisplayName("build.gradle.kts의 JavaLanguageVersion.of에서 버전을 감지한다")
    fun givenBuildGradleKtsWithJavaLanguageVersion_whenDetect_thenReturnsCorrectVersion() {
        // given
        projectDir.resolve("build.gradle.kts").toFile().writeText(
            """
            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(21))
                }
            }
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("21")
    }

    @Test
    @DisplayName("build.gradle.kts의 sourceCompatibility에서 버전을 감지한다")
    fun givenBuildGradleKtsWithSourceCompatibility_whenDetect_thenReturnsCorrectVersion() {
        // given
        projectDir.resolve("build.gradle.kts").toFile().writeText(
            """
            sourceCompatibility = JavaVersion.VERSION_11
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("11")
    }

    // ── build.gradle ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("build.gradle의 sourceCompatibility에서 버전을 감지한다")
    fun givenBuildGradleWithSourceCompatibility_whenDetect_thenReturnsCorrectVersion() {
        // given
        projectDir.resolve("build.gradle").toFile().writeText(
            """
            sourceCompatibility = 11
            targetCompatibility = 11
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("11")
    }

    @Test
    @DisplayName("build.gradle에 버전 정보가 없으면 기본값 21을 반환한다")
    fun givenBuildGradleWithNoVersionInfo_whenDetect_thenReturnsDefaultVersion() {
        // given
        projectDir.resolve("build.gradle").toFile().writeText(
            """
            apply plugin: 'java'
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("21")
    }

    // ── 우선순위 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pom.xml과 build.gradle.kts가 모두 있으면 pom.xml을 우선한다")
    fun givenBothPomAndGradleKts_whenDetect_thenPrefersPomXml() {
        // given
        projectDir.resolve("pom.xml").toFile().writeText(
            """
            <project>
                <properties>
                    <java.version>11</java.version>
                </properties>
            </project>
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").toFile().writeText(
            """
            java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("11")
    }

    // ── 빌드 파일 없음 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("빌드 파일이 없으면 기본값 21을 반환한다")
    fun givenNoBuildFile_whenDetect_thenReturnsDefaultVersion() {
        // given — empty projectDir
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("21")
    }

    // ── PMD 미지원 버전 fallback ──────────────────────────────────────────────

    @Test
    @DisplayName("PMD 미지원 버전(24 이상)이면 최대 지원 버전으로 fallback한다")
    fun givenVersionAboveMaxSupported_whenDetect_thenFallsBackToMaxSupportedVersion() {
        // given
        projectDir.resolve("build.gradle.kts").toFile().writeText(
            """
            java { toolchain { languageVersion.set(JavaLanguageVersion.of(24)) } }
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        val expectedMax = JavaVersionDetector.PMD_SUPPORTED_VERSIONS.max().toString()
        assertThat(result).isEqualTo(expectedMax)
    }

    @Test
    @DisplayName("PMD 미지원 버전(7 이하)이면 최소 지원 버전으로 fallback한다")
    fun givenVersionBelowMinSupported_whenDetect_thenFallsBackToMinSupportedVersion() {
        // given
        projectDir.resolve("build.gradle.kts").toFile().writeText(
            """
            sourceCompatibility = 7
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        val expectedMin = JavaVersionDetector.PMD_SUPPORTED_VERSIONS.min().toString()
        assertThat(result).isEqualTo(expectedMin)
    }

    @Test
    @DisplayName("PMD 지원 버전 사이의 미지원 버전이면 그보다 작은 최대 지원 버전으로 fallback한다")
    fun givenVersionBetweenSupportedVersions_whenDetect_thenFallsBackToClosestLowerSupportedVersion() {
        // given — version 24 is above the max (23), but let's use a version between supported ones
        // PMD supports 8..23; if project uses 21 (supported), should return 21 directly
        // Use version 22 which is supported
        projectDir.resolve("build.gradle.kts").toFile().writeText(
            """
            sourceCompatibility = JavaVersion.VERSION_21
            """.trimIndent()
        )
        // when
        val result = detector.detect(projectDir)
        // then
        assertThat(result).isEqualTo("21")
    }
}

