package com.walter.spring.ai.ops.service

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.net.InetSocketAddress
import java.net.ServerSocket

@ExtendWith(MockitoExtension::class)
class LokiServiceTest {

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var valueOperations: ValueOperations<String, String>

    private var httpServer: HttpServer? = null

    @AfterEach
    fun tearDown() {
        httpServer?.stop(0)
    }

    // ── isConfigured ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("config에 URL이 설정된 경우 true 반환")
    fun isConfigured_returnsTrue_whenConfigUrlIsSet() {
        // given
        val lokiService = LokiService(redisTemplate, "http://loki:3100")

        // when
        val result = lokiService.isConfigured()

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("config가 비어있고 Redis에 URL이 있는 경우 true 반환")
    fun isConfigured_returnsTrue_whenConfigIsBlankAndRedisHasUrl() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("lokiUrl")).thenReturn("http://loki:3100")
        val lokiService = LokiService(redisTemplate, "")

        // when
        val result = lokiService.isConfigured()

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("config와 Redis 모두 비어있는 경우 false 반환")
    fun isConfigured_returnsFalse_whenBothConfigAndRedisAreBlank() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("lokiUrl")).thenReturn(null)
        val lokiService = LokiService(redisTemplate, "")

        // when
        val result = lokiService.isConfigured()

        // then
        assertThat(result).isFalse()
    }

    // ── getLokiUrl ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("config에 URL이 있으면 config 값 반환")
    fun getLokiUrl_returnsConfigUrl_whenConfigIsSet() {
        // given
        val lokiService = LokiService(redisTemplate, "http://loki:3100")

        // when
        val result = lokiService.getLokiUrl()

        // then
        assertThat(result).isEqualTo("http://loki:3100")
    }

    @Test
    @DisplayName("config가 비어있으면 Redis 값 반환")
    fun getLokiUrl_returnsRedisUrl_whenConfigIsBlank() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("lokiUrl")).thenReturn("http://redis-loki:3100")
        val lokiService = LokiService(redisTemplate, "")

        // when
        val result = lokiService.getLokiUrl()

        // then
        assertThat(result).isEqualTo("http://redis-loki:3100")
    }

    @Test
    @DisplayName("config와 Redis 모두 비어있으면 빈 문자열 반환")
    fun getLokiUrl_returnsEmptyString_whenBothConfigAndRedisAreBlank() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get("lokiUrl")).thenReturn(null)
        val lokiService = LokiService(redisTemplate, "")

        // when
        val result = lokiService.getLokiUrl()

        // then
        assertThat(result).isEqualTo("")
    }

    // ── setLokiUrl ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("연결 성공 시 Redis에 저장")
    fun setLokiUrl_savesToRedis_whenConnectionSucceeds() {
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
        val lokiService = LokiService(redisTemplate, "")

        // when
        lokiService.setLokiUrl("http://localhost:$port")

        // then
        verify(valueOperations).set("lokiUrl", "http://localhost:$port")
    }

    @Test
    @DisplayName("연결 실패 시 RuntimeException 발생")
    fun setLokiUrl_throwsRuntimeException_whenConnectionFails() {
        // given
        val closedPort = ServerSocket(0).use { it.localPort }
        val lokiService = LokiService(redisTemplate, "")

        // when & then
        assertThatThrownBy { lokiService.setLokiUrl("http://localhost:$closedPort") }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("Cannot connect to Loki at 'http://localhost:$closedPort'")
    }

    @Test
    @DisplayName("연결 실패 시 Redis에 저장되지 않음")
    fun setLokiUrl_doesNotSaveToRedis_whenConnectionFails() {
        // given
        val closedPort = ServerSocket(0).use { it.localPort }
        val lokiService = LokiService(redisTemplate, "")

        // when
        runCatching { lokiService.setLokiUrl("http://localhost:$closedPort") }

        // then
        org.mockito.Mockito.verifyNoInteractions(redisTemplate)
    }
}

