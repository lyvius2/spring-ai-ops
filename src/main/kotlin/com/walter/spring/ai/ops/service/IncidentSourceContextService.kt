package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.connector.dto.LokiQueryResult
import com.walter.spring.ai.ops.service.dto.IncidentSourceContext
import com.walter.spring.ai.ops.service.dto.SourceSnippet
import com.walter.spring.ai.ops.service.dto.StackTraceFrame
import com.walter.spring.ai.ops.util.StackTraceParser
import com.walter.spring.ai.ops.util.extension.extractSourceSnippet
import com.walter.spring.ai.ops.util.extension.resolveSourceFile
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class IncidentSourceContextService(
    private val stackTraceParser: StackTraceParser,
) {
    fun createContext(logResults: LokiQueryResult, sourcePath: Path?): IncidentSourceContext {
        val frames = stackTraceParser.parse(logResults.rawLogText())
        if (sourcePath == null || frames.isEmpty()) {
            return IncidentSourceContext(
                frames = frames,
                snippets = emptyList(),
                unresolvedFrames = frames,
            )
        }

        val snippets = mutableListOf<SourceSnippet>()
        val unresolvedFrames = mutableListOf<StackTraceFrame>()
        val resolvedSnippetKeys = mutableSetOf<String>()

        frames.forEach { frame ->
            val sourceFile = sourcePath.resolveSourceFile(frame)
            if (sourceFile == null) {
                unresolvedFrames.add(frame)
                return@forEach
            }

            val snippet = sourceFile.extractSourceSnippet(sourcePath, frame)
            val snippetKey = "${snippet.filePath}:${snippet.startLine}:${snippet.endLine}:${snippet.focusLine}"
            if (resolvedSnippetKeys.add(snippetKey)) {
                snippets.add(snippet)
            }
        }

        return IncidentSourceContext(
            frames = frames,
            snippets = snippets,
            unresolvedFrames = unresolvedFrames,
        )
    }
}
