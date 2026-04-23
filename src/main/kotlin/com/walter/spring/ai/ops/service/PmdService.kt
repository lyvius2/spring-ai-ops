package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.util.JavaVersionDetector
import net.sourceforge.pmd.PMDConfiguration
import net.sourceforge.pmd.PmdAnalysis
import net.sourceforge.pmd.lang.LanguageRegistry
import org.springframework.stereotype.Service
import java.nio.file.Path


@Service
class PmdService(
    private val javaVersionDetector: JavaVersionDetector
) {
    fun executeAnalyze(projectPath: Path): String {
        val pmdConfig = PMDConfiguration()
        pmdConfig.setDefaultLanguageVersion(
            LanguageRegistry.PMD.getLanguageById("java")?.getVersion(javaVersionDetector.detect(projectPath))
        )
        pmdConfig.addInputPath(projectPath)
        pmdConfig.addRuleSet("category/java/bestpractices.xml");
        pmdConfig.addRuleSet("category/java/errorprone.xml");
        pmdConfig.addRuleSet("category/java/performance.xml");
        pmdConfig.threads = 2

        val markdown = StringBuilder()
        markdown.append("## Code Risk Analysis Result by PMD\n\n")

        try {
            PmdAnalysis.create(pmdConfig).use { pmd ->
                val violations = pmd.performAnalysisAndCollectReport().violations
                if (violations.isEmpty()) {
                    markdown.append("No issues were found.\n")
                    return markdown.toString()
                }

                violations
                    .groupBy { it.fileId.originalPath.toString() }
                    .forEach { (fileName, fileViolations) ->
                        markdown.append("### ").append(fileName).append("\n\n")
                        markdown.append("| Line | Severity | Rule | Description |\n")
                        markdown.append("|------|----------|------|-------------|\n")
                        fileViolations.forEach { v ->
                            markdown.append(
                                "| ${v.beginLine} | ${v.rule.priority.name} | ${v.rule.name} | ${v.description.replace("|", "\\|")} |\n"
                            )
                        }
                        markdown.append("\n")
                    }
                markdown.append("---\n")
                markdown.append("**Issues: ${violations.size} Cases**\n")
            }
        } catch (e: Exception) {
            markdown.append("An error occurred during analysis: ").append(e.message).append("\n")
        }
        return markdown.toString()
    }
}