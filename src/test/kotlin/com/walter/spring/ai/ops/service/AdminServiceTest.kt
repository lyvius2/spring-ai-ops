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
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
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
        val existing = objectMapper.writeValueAsString(Administrator("admin", "encoded", null, null))
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
        val json = objectMapper.writeValueAsString(Administrator("admin", encoded, null, null))
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
        val json = objectMapper.writeValueAsString(Administrator("admin", encoded, null, null))
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
        val json = objectMapper.writeValueAsString(Administrator("admin", encoded, null, null))
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
        val json = objectMapper.writeValueAsString(Administrator("admin", encoded, null, null))
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
        val json = objectMapper.writeValueAsString(Administrator("admin", encoded, null, null))
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

    // ── getAdminByUsername ────────────────────────────────────────────────────

    @Test
    @DisplayName("username에 해당하는 관리자가 있으면 반환한다")
    fun givenExistingUsername_whenGetAdminByUsername_thenReturnsAdministrator() {
        // given
        val json = objectMapper.writeValueAsString(listOf(Administrator("admin", "encoded", null, null)))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        val result = adminService.getAdminByUsername("admin")

        // then
        assertThat(result).isNotNull
        assertThat(result!!.username()).isEqualTo("admin")
    }

    @Test
    @DisplayName("username에 해당하는 관리자가 없으면 null을 반환한다")
    fun givenUnknownUsername_whenGetAdminByUsername_thenReturnsNull() {
        // given
        val json = objectMapper.writeValueAsString(listOf(Administrator("admin", "encoded", null, null)))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        val result = adminService.getAdminByUsername("unknown")

        // then
        assertThat(result).isNull()
    }

    @Test
    @DisplayName("Redis에 값이 없으면 null을 반환한다")
    fun givenNoValue_whenGetAdminByUsername_thenReturnsNull() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(null)

        // when
        val result = adminService.getAdminByUsername("admin")

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

    // ── getAdmins ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("리스트 형식 JSON이 저장된 경우 파싱하여 반환한다")
    fun givenListJson_whenGetAdmins_thenReturnsList() {
        // given
        val json = objectMapper.writeValueAsString(listOf(Administrator("admin", "encoded", null, null)))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        val result = adminService.getAdmins()

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].username()).isEqualTo("admin")
    }

    @Test
    @DisplayName("구 단일 객체 형식 JSON이 저장된 경우 backward compat으로 리스트로 반환한다")
    fun givenLegacySingleObjectJson_whenGetAdmins_thenWrapsInList() {
        // given
        val json = objectMapper.writeValueAsString(Administrator("admin", "encoded", null, null))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        val result = adminService.getAdmins()

        // then
        assertThat(result).hasSize(1)
        assertThat(result[0].username()).isEqualTo("admin")
    }

    @Test
    @DisplayName("Redis에 값이 없으면 빈 리스트를 반환한다")
    fun givenNoValue_whenGetAdmins_thenReturnsEmpty() {
        // given
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(null)

        // when
        val result = adminService.getAdmins()

        // then
        assertThat(result).isEmpty()
    }

    // ── getAdminDetails ───────────────────────────────────────────────────────

    @Test
    @DisplayName("admin 목록의 상세 정보(username, createdAt, lastLoginAt)를 반환한다")
    fun givenAdminList_whenGetAdminDetails_thenReturnsDetails() {
        // given
        val now = java.time.Instant.now()
        val json = objectMapper.writeValueAsString(listOf(Administrator("admin", "enc1", now, now), Administrator("operator", "enc2", now, null)))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        val result = adminService.getAdminDetails()

        // then
        assertThat(result.map { it.username }).containsExactlyInAnyOrder("admin", "operator")
        assertThat(result.find { it.username == "admin" }?.createdAt).isNotNull()
        assertThat(result.find { it.username == "operator" }?.lastLoginAt).isNull()
    }

    // ── removeAdmins ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("선택된 계정이 삭제된다")
    fun givenExistingUsername_whenRemoveAdmins_thenRemovesThem() {
        // given
        val json = objectMapper.writeValueAsString(listOf(Administrator("admin", "enc1", null, null), Administrator("operator", "enc2", null, null)))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(json)

        // when
        adminService.removeAdmins(listOf("operator"))

        // then
        verify(valueOperations).set(eq(REDIS_KEY_ADMINISTRATORS), anyString())
    }

    @Test
    @DisplayName("admin 계정은 삭제할 수 없다")
    fun givenAdminUsername_whenRemoveAdmins_thenThrowsException() {
        // when / then
        assertThrows<IllegalArgumentException> {
            adminService.removeAdmins(listOf("admin"))
        }
    }

    @Test
    @DisplayName("빈 리스트로 호출하면 예외가 발생한다")
    fun givenEmptyList_whenRemoveAdmins_thenThrowsException() {
        // when / then
        assertThrows<IllegalArgumentException> {
            adminService.removeAdmins(emptyList())
        }
    }

    // ── createAdmin ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 조건이면 새 admin 계정이 저장된다")
    fun givenValidInput_whenCreateAdmin_thenSavesNewAdmin() {
        // given
        val existingJson = objectMapper.writeValueAsString(listOf(Administrator("admin", "encoded", null, null)))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(existingJson)

        // when
        adminService.createAdmin("newadmin", "NewPass@1", "NewPass@1")

        // then
        verify(valueOperations).set(eq(REDIS_KEY_ADMINISTRATORS), anyString())
    }

    @Test
    @DisplayName("이미 존재하는 username으로는 계정 생성이 불가하다")
    fun givenDuplicateUsername_whenCreateAdmin_thenThrowsException() {
        // given
        val existingJson = objectMapper.writeValueAsString(listOf(Administrator("admin", "encoded", null, null)))
        `when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        `when`(valueOperations.get(REDIS_KEY_ADMINISTRATORS)).thenReturn(existingJson)

        // when / then
        assertThrows<IllegalArgumentException> {
            adminService.createAdmin("admin", "NewPass@1", "NewPass@1")
        }
    }

    @Test
    @DisplayName("password와 confirmPassword가 다르면 계정 생성이 불가하다")
    fun givenMismatchedPasswords_whenCreateAdmin_thenThrowsException() {
        // when / then
        assertThrows<IllegalArgumentException> {
            adminService.createAdmin("newadmin", "NewPass@1", "Different@2")
        }
    }

    @Test
    @DisplayName("password 복잡도를 충족하지 못하면 계정 생성이 불가하다")
    fun givenWeakPassword_whenCreateAdmin_thenThrowsException() {
        // when / then
        assertThrows<IllegalArgumentException> {
            adminService.createAdmin("newadmin", "simple", "simple")
        }
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