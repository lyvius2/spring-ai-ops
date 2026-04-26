package com.walter.spring.ai.ops.sonar.service

import com.walter.spring.ai.ops.sonar.service.dto.SonarPluginInfo
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.ResourcePatternResolver
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.jar.JarInputStream

/**
 * Discovers SonarQube language analysis plugin JARs from the [classpath:sonar-plugins/] directory.
 *
 * Plugin JARs are copied into [build/resources/main/sonar-plugins/] by the [copySonarPlugins]
 * Gradle task at build time. They are read as raw byte resources — NOT added to the Spring Boot
 * classloader — to prevent classpath conflicts with the Jasper/Servlet versions bundled inside
 * each sonar plugin JAR.
 *
 * Metadata (Plugin-Key, Plugin-Name, Plugin-Version) is extracted from each JAR's
 * [META-INF/MANIFEST.MF] via [JarInputStream]. Discovery is lazy and cached on first access.
 */
@Service
class SonarPluginRegistry(private val resourcePatternResolver: ResourcePatternResolver) {

    private val log = LoggerFactory.getLogger(SonarPluginRegistry::class.java)

    private val plugins: Map<String, SonarPluginInfo> by lazy { discoverPlugins() }

    fun getAll(): List<SonarPluginInfo> = plugins.values.toList()

    fun get(key: String): SonarPluginInfo? = plugins[key]

    // ── private helpers ───────────────────────────────────────────────────────────

    private fun discoverPlugins(): Map<String, SonarPluginInfo> {
        val resources = try {
            resourcePatternResolver.getResources("classpath*:sonar-plugins/*.jar")
        } catch (e: Exception) {
            log.warn("Failed to scan sonar-plugins directory: {}", e.message)
            return emptyMap()
        }

        if (resources.isEmpty()) {
            log.info("No SonarQube plugins found in classpath:sonar-plugins/ — static analysis will be skipped")
        }

        return resources.mapNotNull { resource ->
            try {
                toPluginInfo(resource)
            } catch (e: Exception) {
                log.debug("Skipping sonar plugin resource {}: {}", resource.filename, e.message)
                null
            }
        }.associateBy { it.key }
    }

    private fun toPluginInfo(resource: org.springframework.core.io.Resource): SonarPluginInfo? {
        val jarBytes = resource.inputStream.use { it.readBytes() }
        val manifest = JarInputStream(ByteArrayInputStream(jarBytes)).use { it.manifest }
        val attrs = manifest?.mainAttributes ?: return null
        val pluginKey = attrs.getValue("Plugin-Key") ?: return null

        val name     = attrs.getValue("Plugin-Name")    ?: pluginKey
        val version  = attrs.getValue("Plugin-Version") ?: "unknown"
        val filename = resource.filename ?: "$pluginKey.jar"
        val hash     = computeMd5(jarBytes)

        log.info("Discovered SonarQube plugin: {} {} ({})", pluginKey, version, filename)
        return SonarPluginInfo(
            key      = pluginKey,
            name     = name,
            version  = version,
            filename = filename,
            hash     = hash,
            bytes    = jarBytes
        )
    }

    private fun computeMd5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}