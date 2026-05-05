package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.config.exception.ForbiddenException
import com.walter.spring.ai.ops.service.AdminService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

@ExtendWith(MockitoExtension::class)
class PasswordChangeRequiredInterceptorTest {

    @Mock private lateinit var adminService: AdminService
    @Mock private lateinit var request: HttpServletRequest
    @Mock private lateinit var response: HttpServletResponse

    private lateinit var interceptor: PasswordChangeRequiredInterceptor

    @BeforeEach
    fun setUp() {
        interceptor = PasswordChangeRequiredInterceptor(adminService)
        SecurityContextHolder.clearContext()
    }

    private fun authenticateAs(username: String) {
        val auth = UsernamePasswordAuthenticationToken(username, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        val ctx = SecurityContextHolder.createEmptyContext()
        ctx.authentication = auth
        SecurityContextHolder.setContext(ctx)
    }

    // ── blocked paths ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("passwordChangeRequired=true 사용자는 일반 API 접근이 차단된다")
    fun givenPasswordChangeRequired_whenAccessingProtectedEndpoint_thenThrowsForbiddenException() {
        // given
        `when`(request.method).thenReturn("GET")
        `when`(request.requestURI).thenReturn("/api/apps")
        authenticateAs("admin")
        `when`(adminService.isPasswordChangeRequired("admin")).thenReturn(true)

        // when / then
        assertThatThrownBy { interceptor.preHandle(request, response, Any()) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    @DisplayName("passwordChangeRequired=false 사용자는 일반 API 접근이 허용된다")
    fun givenPasswordChangeNotRequired_whenAccessingProtectedEndpoint_thenReturnsTrue() {
        // given
        `when`(request.method).thenReturn("GET")
        `when`(request.requestURI).thenReturn("/api/apps")
        authenticateAs("admin")
        `when`(adminService.isPasswordChangeRequired("admin")).thenReturn(false)

        // when
        val result = interceptor.preHandle(request, response, Any())

        // then
        assertThat(result).isTrue()
    }

    // ── exempt paths ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/auth/password는 passwordChangeRequired=true여도 통과된다")
    fun givenPasswordChangeRequired_whenChangePasswordPath_thenReturnsTrue() {
        // given
        `when`(request.method).thenReturn("POST")
        `when`(request.requestURI).thenReturn("/api/auth/password")

        // when
        val result = interceptor.preHandle(request, response, Any())

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("POST /api/auth/logout은 passwordChangeRequired=true여도 통과된다")
    fun givenPasswordChangeRequired_whenLogoutPath_thenReturnsTrue() {
        // given
        `when`(request.method).thenReturn("POST")
        `when`(request.requestURI).thenReturn("/api/auth/logout")

        // when
        val result = interceptor.preHandle(request, response, Any())

        // then
        assertThat(result).isTrue()
    }

    // ── unauthenticated ────────────────────────────────────────────────────────

    @Test
    @DisplayName("인증되지 않은 요청은 체크 없이 통과된다")
    fun givenNoAuthentication_whenPreHandle_thenReturnsTrue() {
        // given
        `when`(request.method).thenReturn("GET")
        `when`(request.requestURI).thenReturn("/api/apps")

        // when
        val result = interceptor.preHandle(request, response, Any())

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("익명 인증 토큰은 체크 없이 통과된다")
    fun givenAnonymousAuthentication_whenPreHandle_thenReturnsTrue() {
        // given
        `when`(request.method).thenReturn("GET")
        `when`(request.requestURI).thenReturn("/api/apps")
        val anon = AnonymousAuthenticationToken("key", "anonymous", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        val ctx = SecurityContextHolder.createEmptyContext()
        ctx.authentication = anon
        SecurityContextHolder.setContext(ctx)

        // when
        val result = interceptor.preHandle(request, response, Any())

        // then
        assertThat(result).isTrue()
    }
}