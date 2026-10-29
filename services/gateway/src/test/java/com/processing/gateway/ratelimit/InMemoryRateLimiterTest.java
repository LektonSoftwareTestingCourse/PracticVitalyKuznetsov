package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit-тесты {@link InMemoryRateLimiter}: token bucket с подменяемым Ticker,
 * без ожидания реального времени.
 */
class InMemoryRateLimiterTest {

    private static final String KEY = "POST /api/transactions:127.0.0.1";

    private final MutableTicker ticker = new MutableTicker();

    private final InMemoryRateLimiter rateLimiter =
            InMemoryRateLimiter.forTesting(2, 1.0, Duration.ofMinutes(10), 100, ticker);

    @Test
    @DisplayName("В пределах capacity запросы разрешены, сверх лимита — нет")
    void shouldRejectRequestsOverCapacity() {
        // Act
        boolean first = rateLimiter.allowRequest(KEY);
        boolean second = rateLimiter.allowRequest(KEY);
        boolean third = rateLimiter.allowRequest(KEY);

        // Assert
        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(third).isFalse();
    }

    @Test
    @DisplayName("Токены пополняются со временем")
    void shouldRefillTokensOverTime() {
        // Arrange
        rateLimiter.allowRequest(KEY);
        rateLimiter.allowRequest(KEY);

        // Act
        ticker.advance(Duration.ofSeconds(1));
        boolean afterRefill = rateLimiter.allowRequest(KEY);

        // Assert
        assertThat(afterRefill).isTrue();
    }

    @Test
    @DisplayName("Разные ключи имеют независимые корзины")
    void shouldKeepSeparateBucketsPerKey() {
        // Arrange
        rateLimiter.allowRequest(KEY);
        rateLimiter.allowRequest(KEY);

        // Act / Assert
        assertThat(rateLimiter.allowRequest(KEY)).isFalse();
        assertThat(rateLimiter.allowRequest("POST /api/transactions:10.0.0.1")).isTrue();
    }

    @Test
    @DisplayName("capacity = 0 -> все запросы отклоняются")
    void shouldRejectEverythingWhenCapacityIsZero() {
        // Arrange
        InMemoryRateLimiter zeroLimiter =
                InMemoryRateLimiter.forTesting(0, 0.0, Duration.ofMinutes(1), 10, ticker);

        // Act / Assert
        assertThat(zeroLimiter.allowRequest(KEY)).isFalse();
    }

    @Test
    @DisplayName("cleanUp не падает на пустом кэше")
    void shouldCleanUpWithoutExceptions() {
        // Act / Assert
        assertThatCode(rateLimiter::cleanUp).doesNotThrowAnyException();
    }

    /** Тикер, который двигается только вручную. */
    private static final class MutableTicker implements Ticker {

        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        private void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }
}
