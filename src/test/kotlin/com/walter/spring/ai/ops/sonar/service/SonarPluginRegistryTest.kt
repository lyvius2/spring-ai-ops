package com.walter.spring.ai.ops.sonar.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.support.ResourcePatternResolver
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

@ExtendWith(MockitoExtension::class)
class SonarPluginRegistryTest {

    @Mock
    private lateinit var resourcePatternResolver: ResourcePatternResolver

    @TempDir
    private lateinit var tempDir: Path

    private lateinit var registry: SonarPluginRegistry

    @BeforeEach
    fun setUp() {
        registry = SonarPluginRegistry(resourcePatternResolver)
    }

    @Test
    @DisplayName("Plugin-Key가 있는 JAR를 발견하면 SonarPluginInfo를 반환한다")
    fun givenPluginJar_whenGetAll_thenReturnsPluginInfo() {
        // given
        val jarFile = buildPluginJar("sonar-kotlin-plugin-3.5.0.jar", "kotlin", "SonarKotlin", "3.5.0")
        given(resourcePatternResolver.getResources("classpath*:sonar-plugins/*.jar"))
            .willReturn(arrayOf(FileSystemResource(jarFile)))

        // when
        val result = registry.getAll()

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].key).isEqualTo("kotlin")
        assertThat(result[0].name).isEqualTo("SonarKotlin")
        assertThat(result[0].version).isEqualTo("3.5.0")
        assertThat(result[0].filename).isEqualTo("sonar-kotlin-plugin-3.5.0.jar")
    }

    @Test
    @DisplayName("Plugin-Key가 없는 JAR는 무시된다")
    fun givenNonPluginJar_whenGetAll_thenReturnsEmptyList() {
        // given
        val jarFile = buildNonPluginJar("some-lib-1.0.jar")
        given(resourcePatternResolver.getResources("classpath*:sonar-plugins/*.jar"))
            .willReturn(arrayOf(FileSystemResource(jarFile)))

        // when
        val result = registry.getAll()

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("여러 플러그인 JAR이 있을 때 모두 발견된다")
    fun givenMultiplePluginJars_whenGetAll_thenReturnsAllPlugins() {
        // given
        val kotlinJar = buildPluginJar("sonar-kotlin-plugin.jar", "kotlin", "SonarKotlin", "3.5.0")
        val javaJar   = buildPluginJar("sonar-java-plugin.jar",   "java",   "SonarJava",   "8.28.0")
        given(resourcePatternResolver.getResources("classpath*:sonar-plugins/*.jar"))
            .willReturn(arrayOf(FileSystemResource(kotlinJar), FileSystemResource(javaJar)))

        // when
        val result = registry.getAll()

        // then
        assertThat(result).hasSize(2)
        assertThat(result.map { it.key }).containsExactlyInAnyOrder("kotlin", "java")
    }

    @Test
    @DisplayName("등록된 key로 get을 호출하면 해당 SonarPluginInfo를 반환한다")
    fun givenPlugin_whenGetByKey_thenReturnsPlugin() {
        // given
        val jarFile = buildPluginJar("sonar-python-plugin.jar", "python", "SonarPython", "5.21.0")
        given(resourcePatternResolver.getResources("classpath*:sonar-plugins/*.jar"))
            .willReturn(arrayOf(FileSystemResource(jarFile)))

        // when
        val result = registry.get("python")

        // then
        assertThat(result).isNotNull
        assertThat(result!!.key).isEqualTo("python")
    }

    @Test
    @DisplayName("등록되지 않은 key로 get을 호출하면 null을 반환한다")
    fun givenUnknownKey_whenGet_thenReturnsNull() {
        // given
        given(resourcePatternResolver.getResources("classpath*:sonar-plugins/*.jar"))
            .willReturn(emptyArray())

        // when
        val result = registry.get("unknown-plugin")

        // then
        assertThat(result).isNull()
    }

    @Test
    @DisplayName("getAll이 반환하는 bytes가 원본 JAR 파일 내용과 일치한다")
    fun givenPlugin_whenGetAll_thenBytesMatchJarFile() {
        // given
        val jarFile = buildPluginJar("sonar-ruby-plugin.jar", "ruby", "SonarRuby", "1.7.0")
        given(resourcePatternResolver.getResources("classpath*:sonar-plugins/*.jar"))
            .willReturn(arrayOf(FileSystemResource(jarFile)))

        // when
        val result = registry.getAll()

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].bytes).isEqualTo(jarFile.readBytes())
    }

    @Test
    @DisplayName("hash는 JAR 바이트의 MD5 값과 일치한다")
    fun givenPlugin_whenGetAll_thenHashIsMd5OfJarBytes() {
        // given
        val jarFile = buildPluginJar("sonar-js-plugin.jar", "js", "SonarJS", "12.3.0")
        given(resourcePatternResolver.getResources("classpath*:sonar-plugins/*.jar"))
            .willReturn(arrayOf(FileSystemResource(jarFile)))

        // when
        val result = registry.getAll()

        // then
        assertThat(result[0].hash).isEqualTo(md5Hex(jarFile.readBytes()))
    }

    @Test
    @DisplayName("getResources 호출 시 예외가 발생하면 빈 리스트를 반환한다")
    fun givenResourceScanFails_whenGetAll_thenReturnsEmptyList() {
        // given
        given(resourcePatternResolver.getResources("classpath*:sonar-plugins/*.jar"))
            .willThrow(RuntimeException("scan failed"))

        // when
        val result = registry.getAll()

        // then
        assertThat(result).isEmpty()
    }

    // ── test data helpers ─────────────────────────────────────────────────────────

    private fun buildPluginJar(filename: String, key: String, name: String, version: String): File {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Plugin-Key")]     = key
            mainAttributes[Attributes.Name("Plugin-Name")]    = name
            mainAttributes[Attributes.Name("Plugin-Version")] = version
        }
        val jarFile = tempDir.resolve(filename).toFile()
        JarOutputStream(jarFile.outputStream(), manifest).use { jar ->
            jar.putNextEntry(ZipEntry("dummy.class"))
            jar.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
            jar.closeEntry()
        }
        return jarFile
    }

    private fun buildNonPluginJar(filename: String): File {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        val jarFile = tempDir.resolve(filename).toFile()
        JarOutputStream(jarFile.outputStream(), manifest).use { }
        return jarFile
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
