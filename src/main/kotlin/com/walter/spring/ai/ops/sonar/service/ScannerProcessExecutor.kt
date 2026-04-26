package com.walter.spring.ai.ops.sonar.service

import org.springframework.stereotype.Component
import java.io.File

/**
 * Infrastructure-level wrapper for executing sonar-scanner as a child process.
 *
 * Scanner stdout and stderr are forwarded to the parent process (inheritIO) so output
 * appears in the application log. Exit code 0 indicates successful analysis.
 */
@Component
class ScannerProcessExecutor {

    fun execute(command: List<String>, workDir: File): Int {
        val process = ProcessBuilder(command)
            .directory(workDir)
            .inheritIO()
            .start()
        return process.waitFor()
    }
}
