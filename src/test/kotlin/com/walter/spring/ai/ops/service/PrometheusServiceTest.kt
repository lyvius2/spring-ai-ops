package com.walter.spring.ai.ops.service

import com.sun.net.httpserver.HttpServer
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_PROMETHEUS_URL
import com.walter.spring.ai.ops.connector.PrometheusConnector
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryInquiry
import com.walter.spring.ai.ops.connector.dto.PrometheusQueryResult
import com.walter.spring.ai.ops.util.MetricHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.net.InetSocketAddress
import java.net.ServerSocket

@ExtendWith(MockitoExtension::class)
class PrometheusServiceTest {

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var valueOperations: ValueOperations<String, String>

    @Mock
    private lateinit var prometheusConnector: PrometheusConnector

    @Mock
    private lateinit var metricHandler: MetricHandler

    private var httpServer: com.sun.net.httpserver.HttpServer? = null

    @AfterEach
    fun tearDown() {
        httpServer?.stop(0)
    }

    // ── isConfigured ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis와 config 모두 비어있으면 false 반환")
    fun givenNeitherRedisNorConfig_whenIsConfigured_thenReturnsFalse() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_PROMETHEUS_URL)).thenReturn(null)
        val service = PrometheusService(redisTemplate, prometheusConnector, metricHandler, "")

        // when
        val result = service.isConfigured()

        // then
        assertThat(result).isFalse()
    }

    @Test
    @DisplayName("Redis에 URL이 있으면 true 반환")
    fun givenRedisHasUrl_whenIsConfigured_thenReturnsTrue() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_PROMETHEUS_URL)).thenReturn("http://prometheus:9090")
        val service = PrometheusService(redisTemplate, prometheusConnector, metricHandler, "")

        // when
        val result = service.isConfigured()

        // then
        assertThat(result).isTrue()
    }

    // ── getPrometheusUrl ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis와 config 둘 다 값이 있으면 Redis 값 우선 반환")
    fun givenBothRedisAndConfig_whenGetPrometheusUrl_thenReturnsRedisValue() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_PROMETHEUS_URL)).thenReturn("http://redis-prom:9090")
        val service = PrometheusService(redisTemplate, prometheusConnector, metricHandler, "http://config-prom:9090")

        // when
        val result = service.getPrometheusUrl()

        // then
        assertThat(result).isEqualTo("http://redis-prom:9090")
    }

    @Test
    @DisplayName("Redis가 비어있으면 config 값 반환")
    fun givenRedisBlank_whenGetPrometheusUrl_thenReturnsConfigValue() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_PROMETHEUS_URL)).thenReturn(null)
        val service = PrometheusService(redisTemplate, prometheusConnector, metricHandler, "http://config-prom:9090")

        // when
        val result = service.getPrometheusUrl()

        // then
        assertThat(result).isEqualTo("http://config-prom:9090")
    }

    // ── setPrometheusUrl ──────────────────────────────────────────────────────

    @Test
    @DisplayName("빈 URL 저장 시 검증 없이 Redis에 저장")
    fun givenBlankUrl_whenSetPrometheusUrl_thenSavesToRedisWithoutValidation() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        val service = PrometheusService(redisTemplate, prometheusConnector, metricHandler, "")

        // when
        service.setPrometheusUrl("")

        // then
        verify(valueOperations).set(REDIS_KEY_PROMETHEUS_URL, "")
    }

    @Test
    @DisplayName("유효한 URL 연결 성공 시 Redis에 저장")
    fun givenValidUrl_whenSetPrometheusUrl_thenSavesToRedis() {
        // given
        httpServer = HttpServer.create(InetSocketAddress(0), 0).also { server ->
            server.createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.start()
        }
        val port = httpServer!!.address.port
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        val service = PrometheusService(redisTemplate, prometheusConnector, metricHandler, "")

        // when
        service.setPrometheusUrl("http://localhost:$port")

        // then
        verify(valueOperations).set(REDIS_KEY_PROMETHEUS_URL, "http://localhost:$port")
    }

    @Test
    @DisplayName("연결 불가 URL 입력 시 RuntimeException 발생 및 Redis 미저장")
    fun givenUnreachableUrl_whenSetPrometheusUrl_thenThrowsAndDoesNotSave() {
        // given
        val closedPort = ServerSocket(0).use { it.localPort }
        val service = PrometheusService(redisTemplate, prometheusConnector, metricHandler, "")

        // when & then
        assertThatThrownBy { service.setPrometheusUrl("http://localhost:$closedPort") }
            .isInstanceOf(RuntimeException::class.java)
        verifyNoInteractions(redisTemplate)
    }

    // ── executeMetricQuery ────────────────────────────────────────────────────

    @Test
    @DisplayName("Prometheus connector 호출 결과 반환")
    fun givenInquiry_whenExecuteMetricQuery_thenReturnsConnectorResult() {
        // given
        val inquiry = PrometheusQueryInquiry(query = "{job=\"api\"}", start = "1700000000", end = "1700003600")
        val expected = PrometheusQueryResult(status = "success")
        `when`(prometheusConnector.queryRange(inquiry)).thenReturn(expected)
        val service = PrometheusService(redisTemplate, prometheusConnector, metricHandler, "")

        // when
        val result = service.executeMetricQuery(inquiry)

        // then
        assertThat(result).isEqualTo(expected)
        verify(prometheusConnector).queryRange(inquiry)
    }
}

