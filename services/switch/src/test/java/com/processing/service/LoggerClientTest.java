package com.processing.service;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit-тесты {@link LoggerClient}: публикация в RabbitMQ и деградация
 * при недоступности брокера.
 */
@ExtendWith(MockitoExtension.class)
class LoggerClientTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private LoggerClient loggerClient;

    @BeforeEach
    void setUp() {
        loggerClient = new LoggerClient(rabbitTemplate);
    }

    @Test
    @DisplayName("Брокер принял сообщение -> log() возвращает true")
    void shouldReturnTrueWhenMessageIsPublished() {
        // Arrange
        TransactionRequest transaction = transaction();

        // Act
        boolean logged = loggerClient.log(transaction);

        // Assert
        assertThat(logged).isTrue();
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY), eq(transaction));
    }

    @Test
    @DisplayName("Брокер недоступен -> log() возвращает false")
    void shouldReturnFalseWhenBrokerIsUnavailable() {
        // Arrange
        TransactionRequest transaction = transaction();
        doThrow(new AmqpException("broker is down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Act
        boolean logged = loggerClient.log(transaction);

        // Assert
        assertThat(logged).isFalse();
    }

    private TransactionRequest transaction() {
        return new TransactionRequest(
                UUID.randomUUID(),
                "0100",
                "000001",
                "625001000123",
                "4000001234567890",
                "000000",
                new BigDecimal("1000"),
                "643",
                "TERM0001",
                "POS",
                "MERCH0000000002",
                "5045",
                "ACQ002",
                "ISS001",
                null,
                TransactionStatus.APPROVED,
                null,
                "AB12CD",
                10,
                Instant.now(),
                Instant.now());
    }
}
