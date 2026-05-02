package com.walter.spring.ai.ops.util

import com.walter.spring.ai.ops.connector.PrometheusConnector
import com.walter.spring.ai.ops.connector.dto.PrometheusData
import com.walter.spring.ai.ops.connector.dto.PrometheusMetricSeries
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryInquiry
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant

private fun <T> anyObject(): T = Mockito.any() as T

@ExtendWith(MockitoExtension::class)
class MetricHandlerTest {

    @Mock
    private lateinit var prometheusConnector: PrometheusConnector

    private lateinit var metricHandler: MetricHandler

    @BeforeEach
    fun setUp() {
        metricHandler = MetricHandler(prometheusConnector)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun successResult(vararg points: Pair<Long, Double>): PrometheusQueryResult =
        PrometheusQueryResult(
            status = "success",
            data = PrometheusData(
                resultType = "matrix",
                result = listOf(
                    PrometheusMetricSeries(
                        values = points.map { (ts, v) -> listOf(ts.toString(), v.toString()) }
                    )
                )
            )
        )

    private fun emptyResult(): PrometheusQueryResult =
        PrometheusQueryResult(status = "success", data = PrometheusData(resultType = "matrix"))

    // ── getApplicationMetrics ─────────────────────────────────────────────────

    @Test
    @DisplayName("정상 응답 시 applicationName이 요청한 앱 이름으로 설정된다")
    fun givenEmptyMetricData_whenGetApplicationMetrics_thenApplicationNameIsSet() {
        // given
        `when`(prometheusConnector.queryRange(anyObject())).thenReturn(emptyResult())
        val now = Instant.now()
        val end = now.epochSecond

        // when
        val result = metricHandler.getApplicationMetrics("my-app", end - 3600, end, now)

        // then
        assertThat(result.applicationName).isEqualTo("my-app")
    }

    @Test
    @DisplayName("메모리 used/allocated 데이터가 있을 때 memoryUsedPercent, memoryUsedMb, memoryAllocatedMb가 계산된다")
    fun givenMemoryData_whenGetApplicationMetrics_thenMemoryFieldsAreCalculated() {
        // given
        val now = Instant.now()
        val end = now.epochSecond
        val memoryUsedBytes = 512.0 * 1024 * 1024    // 512 MB
        val memoryAllocatedBytes = 1024.0 * 1024 * 1024 // 1024 MB

        `when`(prometheusConnector.queryRange(anyObject())).thenAnswer { invocation ->
            val inquiry = invocation.getArgument<PrometheusQueryInquiry>(0)
            when {
                inquiry.query.contains("jvm_memory_used_bytes") -> successResult((end - 60) to memoryUsedBytes)
                inquiry.query.contains("jvm_memory_committed_bytes") -> successResult((end - 60) to memoryAllocatedBytes)
                else -> emptyResult()
            }
        }

        // when
        val result = metricHandler.getApplicationMetrics("my-app", end - 3600, end, now)

        // then
        assertThat(result.memoryUsedMb).isCloseTo(512.0, Offset.offset(0.1))
        assertThat(result.memoryAllocatedMb).isCloseTo(1024.0, Offset.offset(0.1))
        assertThat(result.memoryUsedPercent).isCloseTo(50.0, Offset.offset(0.1))
    }

    @Test
    @DisplayName("메트릭 데이터가 없을 때 메모리 관련 필드가 전부 null이다")
    fun givenNoMetricData_whenGetApplicationMetrics_thenMemoryFieldsAreNull() {
        // given
        `when`(prometheusConnector.queryRange(anyObject())).thenReturn(emptyResult())
        val now = Instant.now()
        val end = now.epochSecond

        // when
        val result = metricHandler.getApplicationMetrics("my-app", end - 3600, end, now)

        // then
        assertThat(result.memoryUsedPercent).isNull()
        assertThat(result.memoryUsedMb).isNull()
        assertThat(result.memoryAllocatedMb).isNull()
    }

    @Test
    @DisplayName("uptime 데이터가 있을 때 uptimeSeconds와 startedAt이 설정된다")
    fun givenUptimeData_whenGetApplicationMetrics_thenUptimeIsSet() {
        // given
        val now = Instant.now()
        val end = now.epochSecond
        val uptimeSeconds = 3600.0

        `when`(prometheusConnector.queryRange(anyObject())).thenAnswer { invocation ->
            val inquiry = invocation.getArgument<PrometheusQueryInquiry>(0)
            when {
                inquiry.query.contains("process_uptime_seconds") -> successResult((end - 60) to uptimeSeconds)
                else -> emptyResult()
            }
        }

        // when
        val result = metricHandler.getApplicationMetrics("my-app", end - 3600, end, now)

        // then
        assertThat(result.uptime?.uptimeSeconds).isEqualTo(uptimeSeconds)
        assertThat(result.uptime?.startedAt).isNotNull()
    }

    @Test
    @DisplayName("uptime 데이터가 없을 때 uptimeSeconds가 null이다")
    fun givenNoUptimeData_whenGetApplicationMetrics_thenUptimeSecondsIsNull() {
        // given
        `when`(prometheusConnector.queryRange(anyObject())).thenReturn(emptyResult())
        val now = Instant.now()
        val end = now.epochSecond

        // when
        val result = metricHandler.getApplicationMetrics("my-app", end - 3600, end, now)

        // then
        assertThat(result.uptime?.uptimeSeconds).isNull()
    }

    @Test
    @DisplayName("CPU 시리즈가 System과 Process 두 항목으로 구성된다")
    fun givenConnector_whenGetApplicationMetrics_thenCpuUsageHasSystemAndProcess() {
        // given
        `when`(prometheusConnector.queryRange(anyObject())).thenReturn(emptyResult())
        val now = Instant.now()
        val end = now.epochSecond

        // when
        val result = metricHandler.getApplicationMetrics("my-app", end - 3600, end, now)

        // then
        assertThat(result.cpuUsage).hasSize(2)
        assertThat(result.cpuUsage.map { it.name }).containsExactly("System", "Process")
    }

    @Test
    @DisplayName("HTTP 상태 시리즈가 2xx, 4xx, 5xx 세 항목으로 구성된다")
    fun givenConnector_whenGetApplicationMetrics_thenHttpStatusHasThreeStatusGroups() {
        // given
        `when`(prometheusConnector.queryRange(anyObject())).thenReturn(emptyResult())
        val now = Instant.now()
        val end = now.epochSecond

        // when
        val result = metricHandler.getApplicationMetrics("my-app", end - 3600, end, now)

        // then
        assertThat(result.httpStatus).hasSize(3)
        assertThat(result.httpStatus.map { it.name }).containsExactly("2xx", "4xx", "5xx")
    }

    @Test
    @DisplayName("open files 데이터가 있을 때 집계된 포인트 목록이 반환된다")
    fun givenOpenFilesData_whenGetApplicationMetrics_thenOpenFilesContainsAggregatedPoints() {
        // given
        val now = Instant.now()
        val end = now.epochSecond

        `when`(prometheusConnector.queryRange(anyObject())).thenAnswer { invocation ->
            val inquiry = invocation.getArgument<PrometheusQueryInquiry>(0)
            when {
                inquiry.query.contains("process_files_open_files") ->
                    successResult((end - 120) to 100.0, (end - 60) to 110.0)
                else -> emptyResult()
            }
        }

        // when
        val result = metricHandler.getApplicationMetrics("my-app", end - 3600, end, now)

        // then
        assertThat(result.openFiles).hasSize(2)
        assertThat(result.openFiles.map { it.value }).containsExactly(100.0, 110.0)
    }

    @Test
    @DisplayName("Prometheus 응답에 errorMessage가 있으면 IllegalStateException이 발생한다")
    fun givenErrorMessageInResponse_whenGetApplicationMetrics_thenThrowsIllegalStateException() {
        // given
        `when`(prometheusConnector.queryRange(anyObject()))
            .thenReturn(PrometheusQueryResult(errorMessage = "Prometheus query failed"))
        val now = Instant.now()
        val end = now.epochSecond

        // when & then
        assertThatThrownBy { metricHandler.getApplicationMetrics("my-app", end - 3600, end, now) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Prometheus query failed")
    }

    @Test
    @DisplayName("Prometheus 응답에 error 필드가 있으면 IllegalStateException이 발생한다")
    fun givenErrorFieldInResponse_whenGetApplicationMetrics_thenThrowsIllegalStateException() {
        // given
        `when`(prometheusConnector.queryRange(anyObject()))
            .thenReturn(PrometheusQueryResult(error = "execution error"))
        val now = Instant.now()
        val end = now.epochSecond

        // when & then
        assertThatThrownBy { metricHandler.getApplicationMetrics("my-app", end - 3600, end, now) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("execution error")
    }
}
