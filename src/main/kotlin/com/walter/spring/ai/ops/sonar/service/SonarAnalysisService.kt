package com.walter.spring.ai.ops.sonar.service

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import com.walter.spring.ai.ops.sonar.service.dto.SonarIssue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Manages sonar-scanner analysis report payloads submitted via the embedded Mock SonarQube API.
 *
 * Responsibilities:
 * - Store raw report ZIP bytes submitted by sonar-scanner to POST /api/ce/submit
 * - Track taskId→projectKey mappings so GET /api/ce/task can return SUCCESS
 * - Extract SonarIssue objects from stored reports by parsing sonar-scanner's protobuf format
 *
 * Scanner report ZIP structure (sonar-scanner-engine convention):
 *   metadata.pb         — single Metadata message (non-delimited)
 *   component-{N}.pb    — single Component message per source component (non-delimited)
 *   issues-{N}.pb       — length-delimited Issue messages for component N
 *   active_rules.pb     — active rule list
 *
 * Issue proto fields (ScannerReport.Issue):
 *   1: string  ruleKey
 *   2: int32   line
 *   3: string  msg
 *   4: enum    severity  (0=UNSET, 1=INFO, 2=MINOR, 3=MAJOR, 4=CRITICAL, 5=BLOCKER)
 *
 * Component proto fields (ScannerReport.Component):
 *   10: string  projectRelativePath
 */
@Service
class SonarAnalysisService {

    private val log = LoggerFactory.getLogger(SonarAnalysisService::class.java)

    private val reportStore = ConcurrentHashMap<String, ByteArray>()
    private val taskStore   = ConcurrentHashMap<String, String>()   // taskId → projectKey

    companion object {
        private val SEVERITY_NAMES = listOf("UNSET", "INFO", "MINOR", "MAJOR", "CRITICAL", "BLOCKER")

        // Proto field numbers — ScannerReport.Issue
        private const val ISSUE_FIELD_RULE_KEY  = 1
        private const val ISSUE_FIELD_LINE       = 2
        private const val ISSUE_FIELD_MSG        = 3
        private const val ISSUE_FIELD_SEVERITY   = 4

        // Proto field numbers — ScannerReport.Component
        private const val COMPONENT_FIELD_RELATIVE_PATH = 10
    }

    // ── report storage ──────────────────────────────────────────────────────────

    fun storeReport(projectKey: String, report: ByteArray) {
        reportStore[projectKey] = report
    }

    fun getLatestReport(projectKey: String): ByteArray? = reportStore[projectKey]

    fun hasReport(projectKey: String): Boolean = reportStore.containsKey(projectKey)

    // ── task tracking ────────────────────────────────────────────────────────────

    fun registerTask(taskId: String, projectKey: String) {
        taskStore[taskId] = projectKey
    }

    fun getProjectKeyForTask(taskId: String): String? = taskStore[taskId]

    // ── issue extraction ──────────────────────────────────────────────────────────

    /**
     * Parses the stored scanner report ZIP and returns all SonarIssue objects found.
     *
     * Correlates issue files (issues-{N}.pb) with component files (component-{N}.pb)
     * to populate the component path field on each issue. Returns an empty list when
     * no issue files are present (expected when no sonar plugins are installed).
     */
    fun extractIssues(projectKey: String): List<SonarIssue> {
        val reportBytes = reportStore[projectKey] ?: run {
            log.debug("No report stored for project '{}'", projectKey)
            return emptyList()
        }

        return try {
            val zipEntries = unzipAll(reportBytes)
            log.info("Scanner report entries for '{}': {}", projectKey, zipEntries.keys.sorted())

            val componentPaths = buildComponentPathMap(zipEntries)
            parseAllIssues(zipEntries, componentPaths)
        } catch (e: Exception) {
            log.warn("Failed to parse scanner report for project '{}': {}", projectKey, e.message)
            emptyList()
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    private fun unzipAll(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    /** Builds componentRef → projectRelativePath from all component-{N}.pb entries. */
    private fun buildComponentPathMap(zipEntries: Map<String, ByteArray>): Map<Int, String> =
        zipEntries
            .filter { (name, _) -> name.startsWith("component-") && name.endsWith(".pb") }
            .mapNotNull { (name, bytes) ->
                val ref = extractRef(name, "component-") ?: return@mapNotNull null
                ref to parseComponentPath(bytes)
            }
            .toMap()

    /** Parses all issue-{N}.pb entries and maps each to its component path. */
    private fun parseAllIssues(zipEntries: Map<String, ByteArray>, componentPaths: Map<Int, String>): List<SonarIssue> {
        val issueFiles = zipEntries.filter { (name, _) -> name.startsWith("issues-") && name.endsWith(".pb") }

        if (issueFiles.isEmpty()) {
            log.info("No issue files in report — no plugins installed or no issues found")
            return emptyList()
        }

        return issueFiles.flatMap { (name, bytes) ->
            val ref = extractRef(name, "issues-")
            val componentPath = componentPaths[ref] ?: ""
            parseIssueFile(bytes, componentPath)
        }
    }

    /**
     * Parses a single component-{N}.pb file (non-delimited protobuf message)
     * and returns the projectRelativePath (field 10).
     */
    private fun parseComponentPath(bytes: ByteArray): String {
        return try {
            val input = CodedInputStream.newInstance(bytes)
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 0) break
                when (WireFormat.getTagFieldNumber(tag)) {
                    COMPONENT_FIELD_RELATIVE_PATH -> return input.readString()
                    else -> input.skipField(tag)
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Parses a single issues-{N}.pb file containing length-delimited Issue messages
     * (written via protobuf's writeDelimitedTo() — varint size prefix + message bytes).
     */
    private fun parseIssueFile(bytes: ByteArray, component: String): List<SonarIssue> {
        val issues = mutableListOf<SonarIssue>()
        val stream = ByteArrayInputStream(bytes)

        while (stream.available() > 0) {
            val msgBytes = readDelimitedMessage(stream) ?: break
            if (msgBytes.isNotEmpty()) {
                issues.add(parseIssueBytes(msgBytes, component))
            }
        }
        return issues
    }

    /**
     * Reads a varint-prefixed byte block (protobuf delimited format) from the stream.
     * Returns null on unexpected EOF or overflow.
     */
    private fun readDelimitedMessage(stream: ByteArrayInputStream): ByteArray? {
        val size = readVarint32(stream)
        if (size < 0) return null
        if (size == 0) return byteArrayOf()

        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val r = stream.read(bytes, read, size - read)
            if (r == -1) return null
            read += r
        }
        return bytes
    }

    /** Reads a 32-bit varint from the stream. Returns -1 on EOF or overflow (> 5 bytes). */
    private fun readVarint32(stream: ByteArrayInputStream): Int {
        var result = 0
        var shift = 0
        for (@Suppress("unused") i in 0 until 5) {
            val b = stream.read()
            if (b == -1) return -1
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return -1
    }

    /**
     * Parses a raw ScannerReport.Issue protobuf message byte array into a SonarIssue.
     * Unknown fields are skipped via skipField() to remain forward-compatible.
     */
    private fun parseIssueBytes(bytes: ByteArray, component: String): SonarIssue {
        var ruleKey = ""
        var line: Int? = null
        var message = ""
        var severityOrdinal = 0

        val input = CodedInputStream.newInstance(bytes)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) break
            when (WireFormat.getTagFieldNumber(tag)) {
                ISSUE_FIELD_RULE_KEY -> ruleKey = input.readString()
                ISSUE_FIELD_LINE     -> { val v = input.readInt32(); if (v > 0) line = v }
                ISSUE_FIELD_MSG      -> message = input.readString()
                ISSUE_FIELD_SEVERITY -> severityOrdinal = input.readEnum()
                else                 -> input.skipField(tag)
            }
        }

        return SonarIssue(
            ruleKey   = ruleKey,
            severity  = SEVERITY_NAMES.getOrElse(severityOrdinal) { "UNKNOWN" },
            component = component,
            line      = line,
            message   = message
        )
    }

    private fun extractRef(filename: String, prefix: String): Int? =
        filename.removePrefix(prefix).removeSuffix(".pb").toIntOrNull()
}
