package com.walter.spring.ai.ops.sonar.config

import org.sonar.api.SonarRuntime
import org.sonar.api.batch.fs.FileSystem
import org.sonar.api.batch.fs.InputFile
import org.sonar.api.batch.fs.InputModule
import org.sonar.api.batch.rule.ActiveRules
import org.sonar.api.batch.sensor.SensorContext
import org.sonar.api.batch.sensor.cache.ReadCache
import org.sonar.api.batch.sensor.cache.WriteCache
import org.sonar.api.batch.sensor.code.NewSignificantCode
import org.sonar.api.batch.sensor.coverage.NewCoverage
import org.sonar.api.batch.sensor.cpd.NewCpdTokens
import org.sonar.api.batch.sensor.error.NewAnalysisError
import org.sonar.api.batch.sensor.highlighting.NewHighlighting
import org.sonar.api.batch.sensor.issue.NewExternalIssue
import org.sonar.api.batch.sensor.issue.NewIssue
import org.sonar.api.batch.sensor.measure.NewMeasure
import org.sonar.api.batch.sensor.rule.NewAdHocRule
import org.sonar.api.batch.sensor.symbol.NewSymbolTable
import org.sonar.api.config.Configuration
import org.sonar.api.config.Settings
import org.sonar.api.scanner.fs.InputProject
import org.sonar.api.utils.Version
import org.springframework.stereotype.Component
import java.io.Serializable
import java.util.Optional

/**
 * Embedded implementation of SonarQube's SensorContext.
 *
 * Provides a Spring-managed SensorContext that runs static analysis in-process,
 * without connecting to an external SonarQube server. Issue-producing methods
 * (newIssue, newMeasure, etc.) are stubbed and will be wired to an in-memory
 * issue store in subsequent iterations.
 */
@Component
@Suppress("DEPRECATION")
class EmbeddedSensorContext : SensorContext {

    private val contextProperties = mutableMapOf<String, String>()

    override fun config(): Configuration = object : Configuration {
        override fun get(key: String): Optional<String> = Optional.empty()
        override fun hasKey(key: String): Boolean = false
        override fun getStringArray(key: String): Array<String> = emptyArray()
    }

    @Deprecated("Deprecated in SonarQube API")
    override fun settings(): Settings =
        throw UnsupportedOperationException("settings() not yet implemented")

    override fun fileSystem(): FileSystem =
        throw UnsupportedOperationException("fileSystem() not yet implemented")

    override fun activeRules(): ActiveRules =
        throw UnsupportedOperationException("activeRules() not yet implemented")

    @Deprecated("Deprecated in SonarQube API")
    override fun module(): InputModule =
        throw UnsupportedOperationException("module() not yet implemented")

    override fun project(): InputProject =
        throw UnsupportedOperationException("project() not yet implemented")

    override fun runtime(): SonarRuntime =
        throw UnsupportedOperationException("runtime() not yet implemented")

    @Deprecated("Deprecated in SonarQube API")
    override fun getSonarQubeVersion(): Version =
        throw UnsupportedOperationException("getSonarQubeVersion() not yet implemented")

    override fun canSkipUnchangedFiles(): Boolean = false

    override fun isCancelled(): Boolean = false

    override fun isCacheEnabled(): Boolean = false

    override fun previousCache(): ReadCache =
        throw UnsupportedOperationException("previousCache() not yet implemented")

    override fun nextCache(): WriteCache =
        throw UnsupportedOperationException("nextCache() not yet implemented")

    override fun <G : Serializable> newMeasure(): NewMeasure<G> =
        throw UnsupportedOperationException("newMeasure() not yet implemented")

    override fun newIssue(): NewIssue =
        throw UnsupportedOperationException("newIssue() not yet implemented")

    override fun newExternalIssue(): NewExternalIssue =
        throw UnsupportedOperationException("newExternalIssue() not yet implemented")

    override fun newAdHocRule(): NewAdHocRule =
        throw UnsupportedOperationException("newAdHocRule() not yet implemented")

    override fun newHighlighting(): NewHighlighting =
        throw UnsupportedOperationException("newHighlighting() not yet implemented")

    override fun newSymbolTable(): NewSymbolTable =
        throw UnsupportedOperationException("newSymbolTable() not yet implemented")

    override fun newCoverage(): NewCoverage =
        throw UnsupportedOperationException("newCoverage() not yet implemented")

    override fun newCpdTokens(): NewCpdTokens =
        throw UnsupportedOperationException("newCpdTokens() not yet implemented")

    override fun newSignificantCode(): NewSignificantCode =
        throw UnsupportedOperationException("newSignificantCode() not yet implemented")

    override fun newAnalysisError(): NewAnalysisError =
        throw UnsupportedOperationException("newAnalysisError() not yet implemented")

    override fun addContextProperty(key: String, value: String) {
        contextProperties[key] = value
    }

    override fun markForPublishing(inputFile: InputFile) {
        // no-op: embedded context does not publish to a SonarQube server
    }

    override fun markAsUnchanged(inputFile: InputFile) {
        // no-op: embedded context does not track file change state
    }

    fun getContextProperties(): Map<String, String> = contextProperties.toMap()
}
