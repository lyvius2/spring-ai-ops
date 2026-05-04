package com.walter.spring.ai.ops.config.aspect

import com.walter.spring.ai.ops.config.exception.ForbiddenException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

class AdminOnlyAspectTest {

    private val aspect = AdminOnlyAspect()

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    @DisplayName("인증된 사용자가 admin이면 예외가 발생하지 않는다")
    fun givenAdminUser_whenCheckAdminOnly_thenNoException() {
        // given
        val auth = UsernamePasswordAuthenticationToken("admin", null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        SecurityContextHolder.getContext().authentication = auth

        // when / then — no exception expected
        aspect.checkAdminOnly()
    }

    @Test
    @DisplayName("인증된 사용자가 admin이 아니면 ForbiddenException이 발생한다")
    fun givenNonAdminUser_whenCheckAdminOnly_thenThrowsForbiddenException() {
        // given
        val auth = UsernamePasswordAuthenticationToken("operator", null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        SecurityContextHolder.getContext().authentication = auth

        // when / then
        val ex = assertThrows<ForbiddenException> {
            aspect.checkAdminOnly()
        }
        assertThat(ex.message).contains("admin")
    }

    @Test
    @DisplayName("인증 정보가 없으면 ForbiddenException이 발생한다")
    fun givenNoAuthentication_whenCheckAdminOnly_thenThrowsForbiddenException() {
        // given — no authentication set in SecurityContextHolder

        // when / then
        assertThrows<ForbiddenException> {
            aspect.checkAdminOnly()
        }
    }
}