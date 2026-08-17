package com.walter.spring.ai.ops.connector.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

class InMemoryCacheStoreConnectorTest {
    private val connector = InMemoryCacheStoreConnector()

    @Test
    @DisplayName("값과 Set 데이터를 메모리에 저장하고 조회한다")
    fun givenValues_whenStoreAndRead_thenReturnsStoredData() {
        // given
        connector.set("value-key", "value")
        connector.addToSet("set-key", "first")
        connector.addToSet("set-key", "second")

        // when
        val value = connector.get("value-key")
        val values = connector.getSet("set-key")

        // then
        assertThat(value).isEqualTo("value")
        assertThat(values).containsExactlyInAnyOrder("first", "second")
    }

    @Test
    @DisplayName("시간순 데이터는 TTL과 무관하게 최신순으로 조회한다")
    fun givenTimeOrderedValues_whenRead_thenReturnsDescendingWithoutTtlExpiration() {
        // given
        connector.addToTimeOrderedSet("history", "first", retentionHours = 0)
        Thread.sleep(2)
        connector.addToTimeOrderedSet("history", "second", retentionHours = 0)

        // when
        val result = connector.getTimeOrderedSetDescending("history")

        // then
        assertThat(result).containsExactly("second", "first")
    }

    @Test
    @DisplayName("락 영역의 결과를 반환하고 실행 후 락을 해제한다")
    fun givenLockKey_whenWithLock_thenReturnsBlockResultAndReleasesLock() {
        // given
        val timeout = Duration.ofMillis(10)

        // when
        val first = connector.withLock("lock", timeout, timeout, timeout) { "first" }
        val second = connector.withLock("lock", timeout, timeout, timeout) { "second" }

        // then
        assertThat(first).isEqualTo("first")
        assertThat(second).isEqualTo("second")
    }
}
