package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link InMemoryCircuitBreaker} с управляемыми часами:
 * проверяются переходы CLOSED → OPEN → HALF_OPEN → CLOSED.
 */
class InMemoryCircuitBreakerTest {

    private static final String SERVICE = "authorization";

    private static final Duration OPEN_DURATION = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private final InMemoryCircuitBreaker breaker =
            InMemoryCircuitBreaker.forTesting(OPEN_DURATION, 3, clock);

    @Test
    @DisplayName("Новый сервис: запросы разрешены")
    void shouldAllowRequestWhenCircuitIsClosed() {
        // Act / Assert
        assertThat(breaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    @DisplayName("Порог отказов достигнут -> контур OPEN, запросы отклоняются")
    void shouldOpenCircuitAfterFailureThreshold() {
        // Act
        breaker.recordFailure(SERVICE);
        breaker.recordFailure(SERVICE);
        boolean allowedBeforeThreshold = breaker.allowRequest(SERVICE);
        breaker.recordFailure(SERVICE);

        // Assert
        assertThat(allowedBeforeThreshold).isTrue();
        assertThat(breaker.allowRequest(SERVICE)).isFalse();
    }

    @Test
    @DisplayName("После openDuration пропускается один пробный запрос")
    void shouldAllowSingleProbeAfterOpenDuration() {
        // Arrange
        openCircuit();
        clock.advance(OPEN_DURATION.plusSeconds(1));

        // Act
        boolean probe = breaker.allowRequest(SERVICE);
        boolean secondRequest = breaker.allowRequest(SERVICE);

        // Assert
        assertThat(probe).isTrue();
        assertThat(secondRequest).isFalse();
    }

    @Test
    @DisplayName("Успех в HALF_OPEN закрывает контур")
    void shouldCloseCircuitAfterSuccessInHalfOpen() {
        // Arrange
        openCircuit();
        clock.advance(OPEN_DURATION.plusSeconds(1));
        breaker.allowRequest(SERVICE);

        // Act
        breaker.recordSuccess(SERVICE);

        // Assert
        assertThat(breaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    @DisplayName("Сбой в HALF_OPEN снова открывает контур")
    void shouldReopenCircuitAfterFailureInHalfOpen() {
        // Arrange
        openCircuit();
        clock.advance(OPEN_DURATION.plusSeconds(1));
        breaker.allowRequest(SERVICE);

        // Act
        breaker.recordFailure(SERVICE);

        // Assert
        assertThat(breaker.allowRequest(SERVICE)).isFalse();
    }

    @Test
    @DisplayName("releaseRequest освобождает пробный слот в HALF_OPEN")
    void shouldReleaseProbeSlot() {
        // Arrange
        openCircuit();
        clock.advance(OPEN_DURATION.plusSeconds(1));
        breaker.allowRequest(SERVICE);

        // Act
        breaker.releaseRequest(SERVICE);

        // Assert
        assertThat(breaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    @DisplayName("Состояния разных сервисов независимы")
    void shouldKeepIndependentStatePerService() {
        // Arrange
        openCircuit();

        // Act / Assert
        assertThat(breaker.allowRequest(SERVICE)).isFalse();
        assertThat(breaker.allowRequest("transaction-logger")).isTrue();
    }

    private void openCircuit() {
        breaker.recordFailure(SERVICE);
        breaker.recordFailure(SERVICE);
        breaker.recordFailure(SERVICE);
    }

    /** Часы, которые можно двигать вперёд без реальных задержек. */
    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant start) {
            this.current = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
