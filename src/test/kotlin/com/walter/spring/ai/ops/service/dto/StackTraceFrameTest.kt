package com.walter.spring.ai.ops.service.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StackTraceFrameTest {

    @Test
    @DisplayName("className에서 packageName과 simpleClassName을 계산함")
    fun givenQualifiedClassName_whenAccessingProperties_thenReturnsPackageNameAndSimpleClassName() {
        // given
        val frame = StackTraceFrame(
            className = "com.example.payment.PaymentService",
            methodName = "pay",
            fileName = "PaymentService.kt",
            lineNumber = 42,
        )

        // when
        val packageName = frame.packageName
        val simpleClassName = frame.simpleClassName

        // then
        assertThat(packageName).isEqualTo("com.example.payment")
        assertThat(simpleClassName).isEqualTo("PaymentService")
    }

    @Test
    @DisplayName("inner class frame이면 simpleClassName에서 inner class suffix를 제거함")
    fun givenInnerClassFrame_whenAccessingSimpleClassName_thenRemovesInnerClassSuffix() {
        // given
        val frame = StackTraceFrame(
            className = "com.example.payment.PaymentService\$Companion",
            methodName = "pay",
            fileName = "PaymentService.kt",
            lineNumber = 42,
        )

        // when
        val simpleClassName = frame.simpleClassName

        // then
        assertThat(simpleClassName).isEqualTo("PaymentService")
    }

    @Test
    @DisplayName("package가 없는 className이면 packageName은 빈 문자열임")
    fun givenClassNameWithoutPackage_whenAccessingProperties_thenReturnsEmptyPackageName() {
        // given
        val frame = StackTraceFrame(
            className = "PaymentService",
            methodName = "pay",
            fileName = "PaymentService.kt",
            lineNumber = null,
        )

        // when
        val packageName = frame.packageName
        val simpleClassName = frame.simpleClassName

        // then
        assertThat(packageName).isEmpty()
        assertThat(simpleClassName).isEqualTo("PaymentService")
    }
}
