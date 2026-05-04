package com.walter.spring.ai.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_ADMINISTRATORS
import com.walter.spring.ai.ops.record.Administrator
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.stereotype.Service
import java.security.SecureRandom

@Service
class AdminService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val passwordEncoder: PasswordEncoder,
) {
    private val log = LoggerFactory.getLogger(AdminService::class.java)

    @EventListener(ApplicationStartedEvent::class)
    fun initializeAdminIfAbsent() {
        val existing = redisTemplate.opsForValue().get(REDIS_KEY_ADMINISTRATORS)
        if (!existing.isNullOrBlank()) {
            return
        }
        val rawPassword = generateRandomPassword()
        val encoded = passwordEncoder.encode(rawPassword)
        val admin = Administrator("admin", encoded)
        redisTemplate.opsForValue().set(REDIS_KEY_ADMINISTRATORS, objectMapper.writeValueAsString(admin))
        log.info("==========================================================")
        log.info("  Admin account initialized.")
        log.info("  username : admin")
        log.info("  password : {}", rawPassword)
        log.info("  Please change the password after first login.")
        log.info("==========================================================")
    }

    fun authenticate(username: String, rawPassword: String): Boolean {
        val admin = getAdmin() ?: return false
        if (admin.username != username) return false
        return passwordEncoder.matches(rawPassword, admin.password)
    }

    fun invalidateSession(httpRequest: HttpServletRequest) {
        SecurityContextHolder.clearContext()
        httpRequest.getSession(false)?.invalidate()
    }

    fun createAuthenticatedSession(username: String, httpRequest: HttpServletRequest) {
        val auth = UsernamePasswordAuthenticationToken(
            username,
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = auth
        SecurityContextHolder.setContext(context)

        val session = httpRequest.getSession(true)
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context)
    }

    fun changePassword(username: String, currentRaw: String, newRaw: String, confirmRaw: String) {
        require(newRaw == confirmRaw) { "New password and confirmation do not match." }
        validatePasswordComplexity(newRaw)

        val admin = getAdmin() ?: throw IllegalStateException("No admin account found.")
        require(admin.username == username) { "Admin account not found." }
        require(passwordEncoder.matches(currentRaw, admin.password)) { "Current password is incorrect." }

        val updated = Administrator(username, passwordEncoder.encode(newRaw))
        redisTemplate.opsForValue().set(REDIS_KEY_ADMINISTRATORS, objectMapper.writeValueAsString(updated))
    }

    fun validatePasswordComplexity(password: String) {
        require(password.length >= 8) {
            "Password must be at least 8 characters long."
        }
        require(password.any { it.isUpperCase() }) {
            "Password must contain at least one uppercase letter."
        }
        require(password.any { it.isLowerCase() }) {
            "Password must contain at least one lowercase letter."
        }
        require(password.any { it.isDigit() }) {
            "Password must contain at least one digit."
        }
        require(password.any { !it.isLetterOrDigit() }) {
            "Password must contain at least one special character."
        }
    }

    fun getAdmin(): Administrator? {
        val value = redisTemplate.opsForValue().get(REDIS_KEY_ADMINISTRATORS) ?: return null
        return runCatching {
            objectMapper.readValue(value, Administrator::class.java)
        }.getOrElse { e ->
            log.warn("Failed to parse administrator record — returning null. cause: {}", e.message)
            null
        }
    }

    private fun generateRandomPassword(): String {
        val upper   = ('A'..'Z').toList()
        val lower   = ('a'..'z').toList()
        val digits  = ('0'..'9').toList()
        val special = listOf('!', '@', '#', '$', '%', '^', '&', '*')
        val all     = upper + lower + digits + special

        val rng = SecureRandom()
        val chars = mutableListOf(
            upper[rng.nextInt(upper.size)],
            lower[rng.nextInt(lower.size)],
            digits[rng.nextInt(digits.size)],
            special[rng.nextInt(special.size)],
        )
        repeat(8) { chars.add(all[rng.nextInt(all.size)]) }
        chars.shuffle(rng)
        return chars.joinToString("")
    }
}