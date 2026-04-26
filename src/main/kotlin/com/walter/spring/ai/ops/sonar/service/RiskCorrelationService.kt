package com.walter.spring.ai.ops.sonar.service

import com.walter.spring.ai.ops.record.CodeRiskIssue
import com.walter.spring.ai.ops.sonar.service.dto.SonarIssue
import org.springframework.stereotype.Service

/**
 * Cross-validates LLM-detected code risk issues against SonarQube static analysis results
 * and assigns final severity based on agreement between the two analyzers.
 *
 * Severity assignment rules:
 *   - Intersection (same file + nearby line in both LLM and SonarQube) → HIGH
 *   - LLM only (no matching SonarQube issue)                           → MEDIUM
 *   - SonarQube only (no matching LLM issue)                           → LOW
 *
 * File matching: normalized path suffix comparison (handles relative vs full paths).
 * Line matching: within [LINE_TOLERANCE] lines, or file-only match when either side has no line.
 */
@Service
class RiskCorrelationService {

    companion object {
        private const val LINE_TOLERANCE = 10
    }

    /**
     * Returns a merged issue list with severity adjusted by cross-validation.
     *
     * Each LLM issue is matched against the SonarQube issue list. Matched LLM issues
     * become HIGH; unmatched LLM issues become MEDIUM. SonarQube issues with no
     * corresponding LLM issue are appended as LOW-severity items.
     */
    fun correlate(llmIssues: List<CodeRiskIssue>, sonarIssues: List<SonarIssue>): List<CodeRiskIssue> {
        val matchedSonarIndices = mutableSetOf<Int>()

        val correlated = llmIssues.map { llmIssue ->
            val matchIdx = sonarIssues.indexOfFirst { sonarIssue ->
                isMatch(llmIssue, sonarIssue)
            }
            if (matchIdx >= 0) {
                matchedSonarIndices.add(matchIdx)
                llmIssue.withSeverity("HIGH")
            } else {
                llmIssue.withSeverity("MEDIUM")
            }
        }

        val sonarOnly = sonarIssues
            .filterIndexed { idx, _ -> idx !in matchedSonarIndices }
            .map { it.toCodeRiskIssue() }

        return correlated + sonarOnly
    }

    // ── matching ──────────────────────────────────────────────────────────────────

    private fun isMatch(llmIssue: CodeRiskIssue, sonarIssue: SonarIssue): Boolean {
        val llmFile = llmIssue.file() ?: return false
        if (!filePathsOverlap(llmFile, sonarIssue.component)) return false

        val llmLine   = parseLine(llmIssue.line())   ?: return true
        val sonarLine = sonarIssue.line              ?: return true
        return kotlin.math.abs(llmLine - sonarLine) <= LINE_TOLERANCE
    }

    /**
     * Returns true when either path ends with the other (normalized to forward-slash, no leading slash).
     * Handles partial vs full path differences between LLM output and SonarQube component paths.
     */
    private fun filePathsOverlap(a: String, b: String): Boolean {
        val normalA = a.replace('\\', '/').trimStart('/')
        val normalB = b.replace('\\', '/').trimStart('/')
        return normalA.endsWith(normalB) || normalB.endsWith(normalA)
    }

    /**
     * Extracts the first integer from a line string such as "42", "40-45", or "40–50".
     * Returns null when the string is blank or cannot be parsed.
     */
    private fun parseLine(lineStr: String?): Int? {
        if (lineStr.isNullOrBlank()) return null
        return lineStr.trim().split(Regex("[-–]")).firstOrNull()?.trim()?.toIntOrNull()
    }

    // ── conversion helpers ────────────────────────────────────────────────────────

    private fun CodeRiskIssue.withSeverity(newSeverity: String): CodeRiskIssue =
        CodeRiskIssue(file(), line(), newSeverity, description(), recommendation(), codeSnippet())

    private fun SonarIssue.toCodeRiskIssue(): CodeRiskIssue = CodeRiskIssue(
        component,
        line?.toString(),
        "LOW",
        "[${ruleKey}] $message",
        "Resolve the SonarQube rule violation.",
        null
    )
}
