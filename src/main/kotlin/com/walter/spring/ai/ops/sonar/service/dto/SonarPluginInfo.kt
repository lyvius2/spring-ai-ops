package com.walter.spring.ai.ops.sonar.service.dto

/**
 * Metadata and raw bytes of a discovered SonarQube language analysis plugin JAR.
 *
 * Populated by [com.walter.spring.ai.ops.sonar.service.SonarPluginRegistry] at startup via
 * classpath MANIFEST.MF scanning. Bytes are served to sonar-scanner via the embedded
 * Mock API at GET /api/plugins/download?plugin={key}.
 */
class SonarPluginInfo(
    val key: String,
    val name: String,
    val version: String,
    val filename: String,
    val hash: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SonarPluginInfo) return false
        return key == other.key && name == other.name && version == other.version &&
               filename == other.filename && hash == other.hash && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + hash.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "SonarPluginInfo(key=$key, name=$name, version=$version, filename=$filename, hash=$hash)"
}