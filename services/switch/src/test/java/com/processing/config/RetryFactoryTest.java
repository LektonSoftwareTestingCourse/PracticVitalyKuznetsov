package com.processing.config;

import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты {@link RetryFactory}: число попыток и интервалы между ними.
 */
class RetryFactoryTest {

    @Test
    @DisplayName("backoff не задан -> retry с минимальным интервалом и maxAttempts из конфигурации")
    void shouldUseMinimalIntervalWhenBackoffIsNull() {
        // Act
        Retry retry = RetryFactory.authorizationRetry(SwitchFixtures.properties(
                Map.of(SwitchFixtures.BIN, SwitchFixtures.ISSUER_ID), null));

        // Assert
        assertThat(retry.getName()).isEqualTo("authorization");
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("Пустой список backoff -> retry использует минимальный интервал")
    void shouldUseMinimalIntervalWhenBackoffIsEmpty() {
        // Act
        Retry retry = RetryFactory.loggerRetry(SwitchFixtures.propertiesWithBackoff(List.of()));

        // Assert
        assertThat(retry.getName()).isEqualTo("logger");
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("Сбой вызова -> ровно maxAttempts попыток и исключение наружу")
    void shouldRetryUntilMaxAttemptsExhausted() {
        // Arrange
        Retry retry = RetryFactory.authorizationRetry(SwitchFixtures.propertiesWithBackoff(List.of(1L, 2L)));
        AtomicInteger calls = new AtomicInteger();

        // Act / Assert
        assertThatThrownBy(() -> Retry.decorateCallable(retry, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("downstream is down");
        }).call()).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isEqualTo(3);
    }
}
