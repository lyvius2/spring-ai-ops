package com.walter.spring.ai.ops.service.dto

data class StackTraceFrame(
    val className: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int?,
) {
    val packageName: String
        get() = className.substringBeforeLast('.', "")

    val simpleClassName: String
        get() = className.substringAfterLast('.').substringBefore('$')

    fun candidateFileNames(): List<String> {
        return buildList {
            fileName?.takeIf { it.endsWith(".kt") || it.endsWith(".java") }?.let { add(it) }
            add("${simpleClassName}.kt")
            add("${simpleClassName}.java")
        }.distinct()
    }
}
