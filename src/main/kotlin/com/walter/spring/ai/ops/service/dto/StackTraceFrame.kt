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
}
