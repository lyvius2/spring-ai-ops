package com.walter.spring.ai.ops.util

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CryptoProviderTest {

    // ── encrypt / decrypt (키 설정됨) ─────────────────────────────────────────

    @Test
    @DisplayName("암호화 후 복호화하면 원본 문자열이 복원된다")
    fun givenConfiguredKey_whenEncryptThenDecrypt_thenReturnsOriginalValue() {
        // given
        val service = CryptoProvider("test-secret-key")
        val original = "sk-openai-secret-api-key-1234567890"

        // when
        val encrypted = service.encrypt(original)
        val decrypted = service.decrypt(encrypted)

        // then
        Assertions.assertThat(decrypted).isEqualTo(original)
    }

    @Test
    @DisplayName("같은 값을 두 번 암호화해도 서로 다른 암호문이 생성된다 (IV 랜덤)")
    fun givenConfiguredKey_whenEncryptTwice_thenProducesDifferentCiphertexts() {
        // given
        val service = CryptoProvider("test-secret-key")
        val plaintext = "my-token"

        // when
        val encrypted1 = service.encrypt(plaintext)
        val encrypted2 = service.encrypt(plaintext)

        // then
        Assertions.assertThat(encrypted1).isNotEqualTo(encrypted2)
    }

    @Test
    @DisplayName("암호문이 평문과 다르다 (암호화 적용 확인)")
    fun givenConfiguredKey_whenEncrypt_thenCiphertextDiffersFromPlaintext() {
        // given
        val service = CryptoProvider("test-secret-key")

        // when
        val encrypted = service.encrypt("my-api-key")

        // then
        Assertions.assertThat(encrypted).isNotEqualTo("my-api-key")
    }

    @Test
    @DisplayName("잘못된 암호문을 복호화하면 null을 반환한다")
    fun givenInvalidCiphertext_whenDecrypt_thenReturnsNull() {
        // given
        val service = CryptoProvider("test-secret-key")

        // when
        val result = service.decrypt("not-a-valid-base64-ciphertext!!!!")

        // then
        Assertions.assertThat(result).isNull()
    }

    @Test
    @DisplayName("다른 키로 암호화된 값을 복호화하면 null을 반환한다")
    fun givenDifferentKey_whenDecrypt_thenReturnsNull() {
        // given
        val encryptingService = CryptoProvider("key-A")
        val decryptingService = CryptoProvider("key-B")
        val encrypted = encryptingService.encrypt("my-secret")

        // when
        val result = decryptingService.decrypt(encrypted)

        // then
        Assertions.assertThat(result).isNull()
    }

    // ── 키 미설정 시 평문 fallback ────────────────────────────────────────────

    @Test
    @DisplayName("secret-key가 빈 문자열이면 encrypt는 평문을 그대로 반환한다")
    fun givenBlankKey_whenEncrypt_thenReturnsPlaintext() {
        // given
        val service = CryptoProvider("")
        val plaintext = "my-token"

        // when
        val result = service.encrypt(plaintext)

        // then
        Assertions.assertThat(result).isEqualTo(plaintext)
    }

    @Test
    @DisplayName("secret-key가 빈 문자열이면 decrypt는 입력값을 그대로 반환한다")
    fun givenBlankKey_whenDecrypt_thenReturnsInputAsIs() {
        // given
        val service = CryptoProvider("")
        val value = "plain-stored-value"

        // when
        val result = service.decrypt(value)

        // then
        Assertions.assertThat(result).isEqualTo(value)
    }
}