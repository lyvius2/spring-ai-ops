package com.walter.spring.ai.ops.service

import com.walter.spring.ai.ops.code.AlertMessageType
import com.walter.spring.ai.ops.service.dto.AlertMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.messaging.simp.SimpMessagingTemplate

@ExtendWith(MockitoExtension::class)
class MessageServiceTest {

    @Mock private lateinit var messagingTemplate: SimpMessagingTemplate

    private lateinit var messageService: MessageService

    @BeforeEach
    fun setUp() {
        messageService = MessageService(messagingTemplate)
    }

    @Test
    @DisplayName("alert 메시지를 /topic/alert 채널로 전송한다")
    fun givenAlertMessage_whenPushAlert_thenSendsToAlertTopic() {
        // given
        val message = AlertMessage(
            type = AlertMessageType.SOURCE_CHECKOUT_FAILED,
            applicationName = "my-app",
            exceptionMessage = "checkout failed",
        )

        // when
        messageService.pushAlert(message)

        // then
        verify(messagingTemplate).convertAndSend("/topic/alert", message)
    }
}
