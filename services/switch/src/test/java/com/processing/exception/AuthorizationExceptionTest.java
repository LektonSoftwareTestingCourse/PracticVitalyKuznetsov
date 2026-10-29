package com.processing.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link AuthorizationException}: формат сообщения и STAN.
 */
class AuthorizationExceptionTest {

    @Test
    @DisplayName("Сообщение содержит STAN, число попыток и текст последней ошибки")
    void shouldExposeStanAndLastErrorInMessage() {
        // Act
        AuthorizationException exception = new AuthorizationException("000001", 3, "connection refused");

        // Assert
        assertThat(exception.getStan()).isEqualTo("000001");
        assertThat(exception.getMessage())
                .contains("STAN=000001")
                .contains("after 3 attempts")
                .contains("connection refused");
    }

    @Test
    @DisplayName("Без текста ошибки в сообщении подставляется unknown")
    void shouldUseUnknownWhenLastErrorIsNull() {
        // Act
        AuthorizationException exception = new AuthorizationException("000002", 1, null);

        // Assert
        assertThat(exception.getMessage()).contains("Last error: unknown");
    }
}
