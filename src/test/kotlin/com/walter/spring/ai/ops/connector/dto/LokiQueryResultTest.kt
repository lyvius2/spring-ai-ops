package com.walter.spring.ai.ops.connector.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class LokiQueryResultTest {

    @Test
    @DisplayName("Loki values에서 timestamp를 제외한 로그 본문만 순서대로 추출함")
    fun givenLokiValues_whenRawLogText_thenReturnsOnlyLogLinesInOrder() {
        // given
        val result = LokiQueryResult(
            data = LokiData(
                result = listOf(
                    LokiStream(
                        values = listOf(
                            listOf("1710000000000000000", "first log line"),
                            listOf("1710000001000000000", "second log line"),
                        )
                    ),
                    LokiStream(
                        values = listOf(
                            listOf("1710000002000000000", "third log line"),
                        )
                    )
                )
            )
        )

        // when
        val rawLogText = result.rawLogText()

        // then
        assertThat(rawLogText).isEqualTo(
            listOf("first log line", "second log line", "third log line")
                .joinToString(System.lineSeparator())
        )
    }

    @Test
    @DisplayName("Loki data가 없으면 빈 문자열을 반환함")
    fun givenNoLokiData_whenRawLogText_thenReturnsEmptyString() {
        // given
        val result = LokiQueryResult(data = null)

        // when
        val rawLogText = result.rawLogText()

        // then
        assertThat(rawLogText).isEmpty()
    }

    @Test
    @DisplayName("로그 본문이 없는 malformed entry는 제외함")
    fun givenMalformedEntries_whenRawLogText_thenSkipsEntriesWithoutLogLine() {
        // given
        val result = LokiQueryResult(
            data = LokiData(
                result = listOf(
                    LokiStream(
                        values = listOf(
                            listOf("1710000000000000000"),
                            listOf("1710000001000000000", "valid log line"),
                            emptyList(),
                        )
                    )
                )
            )
        )

        // when
        val rawLogText = result.rawLogText()

        // then
        assertThat(rawLogText).isEqualTo("valid log line")
    }
}
