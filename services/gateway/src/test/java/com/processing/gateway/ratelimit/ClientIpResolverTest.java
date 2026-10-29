package com.processing.gateway.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link ClientIpResolver}: заголовки прокси в приоритете,
 * затем remote address.
 */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    @DisplayName("X-Forwarded-For: берётся первый адрес из списка")
    void shouldUseFirstAddressFromForwardedHeader() {
        // Arrange
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/transactions")
                        .header("X-Forwarded-For", "1.2.3.4, 5.6.7.8")
                        .build());

        // Act
        String ip = resolver.resolve(exchange);

        // Assert
        assertThat(ip).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("X-Real-IP используется, если X-Forwarded-For отсутствует")
    void shouldUseRealIpHeader() {
        // Arrange
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/transactions")
                        .header("X-Real-IP", " 10.10.10.10 ")
                        .build());

        // Act
        String ip = resolver.resolve(exchange);

        // Assert
        assertThat(ip).isEqualTo("10.10.10.10");
    }

    @Test
    @DisplayName("Без заголовков берётся remote address")
    void shouldFallBackToRemoteAddress() {
        // Arrange
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/transactions")
                        .remoteAddress(new InetSocketAddress("192.168.1.5", 5000))
                        .build());

        // Act
        String ip = resolver.resolve(exchange);

        // Assert
        assertThat(ip).isEqualTo("192.168.1.5");
    }

    @Test
    @DisplayName("Нет ни заголовков, ни remote address -> unknown")
    void shouldReturnUnknownWhenNothingIsAvailable() {
        // Arrange
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/transactions").build());

        // Act
        String ip = resolver.resolve(exchange);

        // Assert
        assertThat(ip).isEqualTo("unknown");
    }

    @Test
    @DisplayName("Пустой X-Forwarded-For игнорируется")
    void shouldIgnoreBlankForwardedHeader() {
        // Arrange
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/transactions")
                        .header("X-Forwarded-For", "   ")
                        .header("X-Real-IP", "8.8.8.8")
                        .build());

        // Act
        String ip = resolver.resolve(exchange);

        // Assert
        assertThat(ip).isEqualTo("8.8.8.8");
    }
}
