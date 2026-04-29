package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.service.dto.SourceSnippet
import com.walter.spring.ai.ops.service.dto.StackTraceFrame
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines

private const val DEFAULT_SNIPPET_RADIUS = 40
private const val DEFAULT_NO_LINE_LIMIT = 80

private val sourceRoots = listOf(
    "src/main/kotlin",
    "src/main/java",
    "src/test/kotlin",
    "src/test/java",
)

private val sourceExtensions = setOf("kt", "java")

fun Path.resolveSourceFile(frame: StackTraceFrame): Path? {
    val directMatch = directSourceCandidates(frame).firstOrNull { it.exists() && it.isRegularFile() }
    if (directMatch != null) {
        return directMatch
    }

    val fallbackFileNames = frame.candidateFileNames()
    if (fallbackFileNames.isEmpty()) {
        return null
    }

    val matchedFiles = Files.walk(this).use { stream ->
        stream
            .filter { path -> path.isRegularFile() }
            .filter { path -> path.extension.lowercase() in sourceExtensions }
            .filter { path -> path.name in fallbackFileNames }
            .sorted()
            .toList()
    }

    if (matchedFiles.isEmpty()) {
        return null
    }

    return matchedFiles.firstOrNull { path -> path.hasPackageDeclaration(frame.packageName) }
        ?: matchedFiles.first()
}

fun Path.extractSourceSnippet(repositoryRoot: Path, frame: StackTraceFrame, radius: Int = DEFAULT_SNIPPET_RADIUS): SourceSnippet {
    val lines = readLines()
    if (lines.isEmpty()) {
        return SourceSnippet(
            filePath = repositoryRoot.relativize(this).invariantSeparatorsPathString,
            startLine = 1,
            endLine = 0,
            focusLine = frame.lineNumber,
            content = "",
        )
    }

    val focusLine = frame.lineNumber?.takeIf { it in 1..lines.size }
    val startLine = if (focusLine != null) {
        (focusLine - radius).coerceAtLeast(1)
    } else {
        1
    }
    val endLine = if (focusLine != null) {
        (focusLine + radius).coerceAtMost(lines.size)
    } else {
        DEFAULT_NO_LINE_LIMIT.coerceAtMost(lines.size)
    }

    val content = (startLine..endLine).joinToString(System.lineSeparator()) { lineNumber ->
        val marker = if (lineNumber == focusLine) ">>" else "  "
        "$marker ${lineNumber.toString().padStart(4)} | ${lines[lineNumber - 1]}"
    }

    return SourceSnippet(
        filePath = repositoryRoot.relativize(this).invariantSeparatorsPathString,
        startLine = startLine,
        endLine = endLine,
        focusLine = focusLine,
        content = content,
    )
}

private fun Path.directSourceCandidates(frame: StackTraceFrame): List<Path> {
    val packagePath = frame.packageName.replace('.', '/')
    return sourceRoots.flatMap { sourceRoot ->
        frame.candidateFileNames().map { fileName ->
            if (packagePath.isBlank()) {
                resolve(sourceRoot).resolve(fileName)
            } else {
                resolve(sourceRoot).resolve(packagePath).resolve(fileName)
            }
        }
    }
}

private fun Path.hasPackageDeclaration(packageName: String): Boolean {
    if (packageName.isBlank()) {
        return false
    }
    return runCatching {
        readLines().asSequence()
            .take(80)
            .map { it.trim() }
            .any { line -> line == "package $packageName" || line.startsWith("package $packageName ") }
    }.getOrDefault(false)
}
