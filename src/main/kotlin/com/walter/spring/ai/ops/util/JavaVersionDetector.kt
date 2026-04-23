package com.walter.spring.ai.ops.util

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import java.io.IOException
import java.lang.String.valueOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

@Component
class JavaVersionDetector {
    private val log = LoggerFactory.getLogger(JavaVersionDetector::class.java)

    companion object {
        val PMD_SUPPORTED_VERSIONS = listOf(
            8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23
        )
        const val DEFAULT_VERSION = 21
    }

    fun detect(projectPath: Path): String {
        val version = resolveVersion(projectPath)
        return valueOf(fallbackToSupported(version))
    }

    private fun resolveVersion(projectPath: Path): Int {
        val pomXml = projectPath.resolve("pom.xml")
        val buildGradleKts = projectPath.resolve("build.gradle.kts")
        val buildGradle = projectPath.resolve("build.gradle")

        return try {
            when {
                Files.exists(pomXml) -> parseMaven(pomXml)
                Files.exists(buildGradleKts) -> parseGradle(buildGradleKts)
                Files.exists(buildGradle) -> parseGradle(buildGradle)
                else -> DEFAULT_VERSION
            }
        } catch (e: Exception) {
            log.error("Failed to detect Java version from project files. Falling back to default version {}. cause: {}", DEFAULT_VERSION, e.message, e)
            DEFAULT_VERSION
        }
    }

    @Throws(Exception::class)
    private fun parseMaven(pomXml: Path): Int {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val builder = factory.newDocumentBuilder()
        val doc: Document = builder.parse(pomXml.toFile())
        doc.documentElement.normalize()

        val priorityTags = listOf("java.version", "maven.compiler.source", "source", "release")
        return priorityTags.firstNotNullOfOrNull { tag -> getXmlTextAsVersion(doc, tag).takeIf { it > 0 } }
            ?: DEFAULT_VERSION
    }

    private fun getXmlTextAsVersion(doc: Document, tagName: String?): Int {
        val nodes: NodeList = doc.getElementsByTagName(tagName)
        for (i in 0 ..< nodes.length) {
            val text = nodes.item(i).textContent.trim()
            val version = parseVersionString(text)
            if (version > 0) {
                return version
            }
        }
        return -1
    }

    @Throws(IOException::class)
    private fun parseGradle(gradleFile: Path): Int {
        val content = Files.readString(gradleFile)
        val patterns = listOf(
            "jvmTarget\\s*=\\s*[\"'](\\d+)[\"']",
            "sourceCompatibility\\s*=\\s*(?:JavaVersion\\.VERSION_)?(\\d+)",
            "targetCompatibility\\s*=\\s*(?:JavaVersion\\.VERSION_)?(\\d+)",
            "JavaLanguageVersion\\.of\\((\\d+)\\)"
        )
        for (pattern in patterns) {
            val version = findByPattern(content, pattern)
            if (version > 0) {
                return version
            }
        }
        return DEFAULT_VERSION
    }

    private fun findByPattern(content: String, regex: String): Int {
        val matcher = Pattern.compile(regex).matcher(content)
        return if (matcher.find()) {
            parseVersionString(matcher.group(1))
        } else {
            -1
        }
    }

    private fun parseVersionString(raw: String?): Int {
        var raw = raw
        if (raw.isNullOrBlank()) {
            return -1
        }
        raw = raw.trim { it <= ' ' }
        return if (raw.startsWith("1.")) {
            raw.substring(2).toIntOrNull() ?: run { catchError(raw) }
        } else {
            raw.toIntOrNull() ?: run { catchError(raw) }
        }
    }

    private fun catchError(raw: String): Int {
        log.error("Could not parse version string : {}", raw)
        return -1
    }

    private fun fallbackToSupported(version: Int): Int {
        if (PMD_SUPPORTED_VERSIONS.contains(version)) {
            return version
        }
        val max = PMD_SUPPORTED_VERSIONS.max()
        if (version > max) {
            return max
        }
        val min = PMD_SUPPORTED_VERSIONS.min()
        if (version < min) {
            return min
        }
        return PMD_SUPPORTED_VERSIONS.filter { it <= version }.max()
    }
}