package com.walter.spring.ai.ops.util

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class CryptoProvider(
    @Value("\${crypto.secret-key:}") private val secretKeyString: String
) {
    private val log = LoggerFactory.getLogger(CryptoProvider::class.java)

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }

    init {
        if (secretKeyString.isBlank()) {
            log.error(
                """

                ====================================================================================================
                FATAL SECURITY CONFIGURATION ERROR
                ====================================================================================================
                crypto.secret-key is not configured.

                Spring AI Ops encrypts Redis-stored API keys and access tokens with AES-256-GCM.
                Starting without crypto.secret-key would risk storing sensitive values as plaintext.

                Set the CRYPTO_SECRET_KEY environment variable or configure crypto.secret-key in application.yml.
                The application will now stop.
                ====================================================================================================

                """.trimIndent()
            )
            throw IllegalStateException("Missing required security property: crypto.secret-key")
        }
    }

    private val aesKey: SecretKey by lazy {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(secretKeyString.toByteArray(Charsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plaintext: String): String {
        val key = aesKey
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(encrypted: String): String? {
        val key = aesKey
        return runCatching {
            val combined = Base64.getDecoder().decode(encrypted)
            check(combined.size > IV_LENGTH) { "Ciphertext is too short" }
            val iv = combined.copyOf(IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse {
            log.warn("Failed to decrypt value from Redis — it may be a plaintext value stored before encryption was introduced. Re-entering the value will encrypt it going forward.")
            null
        }
    }
}
