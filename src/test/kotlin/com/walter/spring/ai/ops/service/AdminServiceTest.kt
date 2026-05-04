package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_ADMINISTRATORS
import com.walter.spring.ai.ops.record.Administrator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository

@ExtendWith(MockitoExtension::class)
class AdminServiceTest {

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var valueOperations: ValueOperations<String, String>

    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val passwordEncoder = BCryptPasswordEncoder()

    private lateinit var adminService: AdminService

    @BeforeEach
    fun setUp() {
        adminService = AdminService(redisTemplate, objectMapper, passwordEncoder)
    }

    // ── initializeAdminIfAbsent ───────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 관리자 정보가 없으면 admin 계정을 생성한다")
    fun givenNoAdminInRedis_whenInitializeAdminIfAbsent_thenCreatesAdmin() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(null)

        // when
        adminService.initializeAdminIfAbsent()

        // then
        verify(valueOperations).set(
            org.mockito.ArgumentMatchers.eq(REDIS_KEY_ADMINISTRATORS),
            org.mockito.ArgumentMatchers.anyString(),
        )
    }

    @Test
    @DisplayName("Redis에 관리자 정보가 이미 있으면 초기화를 건너뛴다")
    fun givenAdminAlreadyInRedis_whenInitializeAdminIfAbsent_thenSkips() {
        // given
        val existing = objectMapper.writeValueAsString(Administrator("admin", "encoded"))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(existing)

        // when
        adminService.initializeAdminIfAbsent()

        // then
        verify(valueOperations, never()).set(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
        )
    }

    // ── authenticate ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("올바른 username과 password가 주어지면 true를 반환한다")
    fun givenCorrectCredentials_whenAuthenticate_thenReturnsTrue() {
        // given
        val rawPassword = "Test@1234"
        val encoded = passwordEncoder.encode(rawPassword)
        val json = objectMapper.writeValueAsString(Administrator("admin", encoded))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        val result = adminService.authenticate("admin", rawPassword)

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("잘못된 password가 주어지면 false를 반환한다")
    fun givenWrongPassword_whenAuthenticate_thenReturnsFalse() {
        // given
        val encoded = passwordEncoder.encode("Test@1234")
        val json = objectMapper.writeValueAsString(Administrator("admin", encoded))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        val result = adminService.authenticate("admin", "wrongPassword")

        // then
        assertThat(result).isFalse()
    }

    @Test
    @DisplayName("잘못된 username이 주어지면 false를 반환한다")
    fun givenWrongUsername_whenAuthenticate_thenReturnsFalse() {
        // given
        val rawPassword = "Test@1234"
        val encoded = passwordEncoder.encode(rawPassword)
        val json = objectMapper.writeValueAsString(Administrator("admin", encoded))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        val result = adminService.authenticate("hacker", rawPassword)

        // then
        assertThat(result).isFalse()
    }

    @Test
    @DisplayName("Redis에 관리자 정보가 없으면 false를 반환한다")
    fun givenNoAdminInRedis_whenAuthenticate_thenReturnsFalse() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(null)

        // when
        val result = adminService.authenticate("admin", "Test@1234")

        // then
        assertThat(result).isFalse()
    }

    // ── changePassword ────────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 조건이 모두 충족되면 비밀번호가 변경된다")
    fun givenValidInput_whenChangePassword_thenUpdatesPassword() {
        // given
        val oldPassword = "OldPass@1"
        val encoded = passwordEncoder.encode(oldPassword)
        val json = objectMapper.writeValueAsString(Administrator("admin", encoded))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        adminService.changePassword("admin", oldPassword, "NewPass@2", "NewPass@2")

        // then
        verify(valueOperations).set(
            org.mockito.ArgumentMatchers.eq(REDIS_KEY_ADMINISTRATORS),
            org.mockito.ArgumentMatchers.anyString(),
        )
    }

    @Test
    @DisplayName("새 비밀번호와 확인 비밀번호가 다르면 예외가 발생한다")
    fun givenMismatchedNewPasswords_whenChangePassword_thenThrowsException() {
        // given / when / then
        assertThrows<IllegalArgumentException> {
            adminService.changePassword("admin", "OldPass@1", "NewPass@2", "Mismatch@3")
        }
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 예외가 발생한다")
    fun givenWrongCurrentPassword_whenChangePassword_thenThrowsException() {
        // given
        val encoded = passwordEncoder.encode("OldPass@1")
        val json = objectMapper.writeValueAsString(Administrator("admin", encoded))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when / then
        assertThrows<IllegalArgumentException> {
            adminService.changePassword("admin", "WrongPass@1", "NewPass@2", "NewPass@2")
        }
    }

    // ── validatePasswordComplexity ────────────────────────────────────────────

    @Test
    @DisplayName("8자 미만이면 예외가 발생한다")
    fun givenTooShortPassword_whenValidatePasswordComplexity_thenThrowsException() {
        assertThrows<IllegalArgumentException> {
            adminService.validatePasswordComplexity("Ab1!")
        }
    }

    @Test
    @DisplayName("대문자가 없으면 예외가 발생한다")
    fun givenNoUppercase_whenValidatePasswordComplexity_thenThrowsException() {
        assertThrows<IllegalArgumentException> {
            adminService.validatePasswordComplexity("abcdef1!")
        }
    }

    @Test
    @DisplayName("소문자가 없으면 예외가 발생한다")
    fun givenNoLowercase_whenValidatePasswordComplexity_thenThrowsException() {
        assertThrows<IllegalArgumentException> {
            adminService.validatePasswordComplexity("ABCDEF1!")
        }
    }

    @Test
    @DisplayName("숫자가 없으면 예외가 발생한다")
    fun givenNoDigit_whenValidatePasswordComplexity_thenThrowsException() {
        assertThrows<IllegalArgumentException> {
            adminService.validatePasswordComplexity("Abcdefg!")
        }
    }

    @Test
    @DisplayName("특수문자가 없으면 예외가 발생한다")
    fun givenNoSpecialChar_whenValidatePasswordComplexity_thenThrowsException() {
        assertThrows<IllegalArgumentException> {
            adminService.validatePasswordComplexity("Abcdef12")
        }
    }

    @Test
    @DisplayName("모든 복잡도 조건을 충족하면 예외가 발생하지 않는다")
    fun givenValidPassword_whenValidatePasswordComplexity_thenNoException() {
        // when / then — no exception expected
        adminService.validatePasswordComplexity("Test@1234")
    }

    // ── getAdmin ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis에 유효한 관리자 JSON이 있으면 Administrator를 반환한다")
    fun givenValidJson_whenGetAdmin_thenReturnsAdministrator() {
        // given
        val json = objectMapper.writeValueAsString(Administrator("admin", "encoded"))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        val result = adminService.getAdmin()

        // then
        assertThat(result).isNotNull
        assertThat(result!!.username()).isEqualTo("admin")
    }

    @Test
    @DisplayName("Redis에 값이 없으면 null을 반환한다")
    fun givenNoValue_whenGetAdmin_thenReturnsNull() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(null)

        // when
        val result = adminService.getAdmin()

        // then
        assertThat(result).isNull()
    }

    @Test
    @DisplayName("Redis에 잘못된 JSON이 있으면 null을 반환한다")
    fun givenInvalidJson_whenGetAdmin_thenReturnsNull() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn("not-valid-json")

        // when
        val result = adminService.getAdmin()

        // then
        assertThat(result).isNull()
    }

    // ── invalidateSession ─────────────────────────────────────────────────────

    @Test
    @DisplayName("로그아웃 시 SecurityContext가 초기화되고 세션이 무효화된다")
    fun givenActiveSession_whenInvalidateSession_thenContextClearedAndSessionInvalidated() {
        // given
        val mockRequest = org.mockito.Mockito.mock(HttpServletRequest::class.java)
        val mockSession = org.mockito.Mockito.mock(HttpSession::class.java)
        `when`(mockRequest.getSession(false)).thenReturn(mockSession)

        // when
        adminService.invalidateSession(mockRequest)

        // then
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify(mockSession).invalidate()
    }

    @Test
    @DisplayName("세션이 없을 때 로그아웃해도 예외가 발생하지 않는다")
    fun givenNoSession_whenInvalidateSession_thenNoException() {
        // given
        val mockRequest = org.mockito.Mockito.mock(HttpServletRequest::class.java)
        `when`(mockRequest.getSession(false)).thenReturn(null)

        // when / then — no exception expected
        adminService.invalidateSession(mockRequest)
    }

    // ── createAuthenticatedSession ────────────────────────────────────────────

    @Test
    @DisplayName("인증 성공 시 SecurityContext와 세션에 인증 정보가 저장된다")
    fun givenUsername_whenCreateAuthenticatedSession_thenSecurityContextAndSessionAreSet() {
        // given
        val mockRequest = org.mockito.Mockito.mock(HttpServletRequest::class.java)
        val mockSession = org.mockito.Mockito.mock(HttpSession::class.java)
        `when`(mockRequest.getSession(true)).thenReturn(mockSession)
        SecurityContextHolder.clearContext()

        // when
        adminService.createAuthenticatedSession("admin", mockRequest)

        // then
        val auth = SecurityContextHolder.getContext().authentication
        assertThat(auth).isNotNull
        assertThat(auth.name).isEqualTo("admin")
        assertThat(auth.authorities.map { it.authority }).contains("ROLE_ADMIN")

        verify(mockSession).setAttribute(
            org.mockito.ArgumentMatchers.eq(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY),
            org.mockito.ArgumentMatchers.any(),
        )
    }
}