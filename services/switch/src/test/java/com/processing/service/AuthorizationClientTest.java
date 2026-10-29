package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.RetryFactory;
import com.processing.config.SwitchFixtures;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit-тесты {@link AuthorizationClient} на {@link MockRestServiceServer}:
 * успешные вызовы, деградация, разомкнутый circuit breaker.
 */
class AuthorizationClientTest {

    private static final String AUTHORIZE_URL = "http://localhost:8083/api/internal/authorize";

    private static final String ROLLBACK_URL = "http://localhost:8083/api/internal/rollback";

    private static final String HEALTH_URL = "http://localhost:8083/health";

    private static final String APPROVED_JSON = """
            {
              "mti": "0100",
              "stan": "000001",
              "rrn": "625001000123",
              "authCode": "AB12CD",
              "responseCode": "00",
              "status": "APPROVED",
              "declineReason": null,
              "processingTimeMs": 10
            }
            """;

    private static final String DECLINED_JSON = """
            {
              "mti": "0100",
              "stan": "000001",
              "rrn": null,
              "authCode": null,
              "responseCode": "54",
              "status": "DECLINED",
              "declineReason": "CARD_EXPIRED",
              "processingTimeMs": 4
            }
            """;

    private static final String INVALID_STATUS_JSON = """
            {
              "mti": "0100",
              "stan": "000001",
              "rrn": "625001000123",
              "authCode": "AB12CD",
              "responseCode": "00",
              "status": "PENDING",
              "declineReason": null,
              "processingTimeMs": 10
            }
            """;

    private static final String ROLLBACK_APPROVED_JSON = """
            {
              "rrn": "625001000123",
              "responseCode": "00",
              "status": "APPROVED",
              "declineReason": null,
              "processingTimeMs": 5
            }
            """;

    @Test
    @DisplayName("authorize: APPROVED от сервиса возвращается клиенту")
    void shouldReturnApprovedResponseWhenAuthorizationServiceResponds() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), CircuitBreaker.ofDefaults("cb"));
        server.expect(requestTo(AUTHORIZE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(APPROVED_JSON, MediaType.APPLICATION_JSON));

        // Act
        AuthorizationResponse response = client.authorize(SwitchFixtures.request(SwitchFixtures.PAN));

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        assertThat(response.rrn()).isEqualTo("625001000123");
        server.verify();
    }

    @Test
    @DisplayName("authorize: DECLINED от сервиса тоже считается валидным ответом")
    void shouldReturnDeclinedResponseWhenAuthorizationServiceDeclines() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), CircuitBreaker.ofDefaults("cb"));
        server.expect(requestTo(AUTHORIZE_URL))
                .andRespond(withSuccess(DECLINED_JSON, MediaType.APPLICATION_JSON));

        // Act
        AuthorizationResponse response = client.authorize(SwitchFixtures.request(SwitchFixtures.PAN));

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_EXPIRED);
    }

    @Test
    @DisplayName("authorize: 5xx -> AuthorizationException после исчерпания попыток")
    void shouldThrowAuthorizationExceptionWhenServiceReturnsServerError() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), CircuitBreaker.ofDefaults("cb"));
        server.expect(ExpectedCount.manyTimes(), requestTo(AUTHORIZE_URL)).andRespond(withServerError());

        // Act / Assert
        AuthorizationRequest request = SwitchFixtures.request(SwitchFixtures.PAN);
        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Authorization service unavailable")
                .hasMessageContaining(SwitchFixtures.STAN);
    }

    @Test
    @DisplayName("authorize: неожиданный статус в ответе -> AuthorizationException")
    void shouldThrowAuthorizationExceptionWhenResponseStatusIsInvalid() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), CircuitBreaker.ofDefaults("cb"));
        server.expect(ExpectedCount.manyTimes(), requestTo(AUTHORIZE_URL))
                .andRespond(withSuccess(INVALID_STATUS_JSON, MediaType.APPLICATION_JSON));

        // Act / Assert
        AuthorizationRequest request = SwitchFixtures.request(SwitchFixtures.PAN);
        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response from Authorization");
    }

    @Test
    @DisplayName("authorize: пустое тело ответа -> AuthorizationException")
    void shouldThrowAuthorizationExceptionWhenResponseBodyIsEmpty() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), CircuitBreaker.ofDefaults("cb"));
        server.expect(ExpectedCount.manyTimes(), requestTo(AUTHORIZE_URL)).andRespond(withSuccess());

        // Act / Assert
        AuthorizationRequest request = SwitchFixtures.request(SwitchFixtures.PAN);
        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response from Authorization");
    }

    @Test
    @DisplayName("authorize: разомкнутый circuit breaker -> AuthorizationException с причиной Circuit breaker open")
    void shouldThrowAuthorizationExceptionWhenCircuitBreakerIsOpen() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build();
        CircuitBreaker circuitBreaker = CircuitBreaker.of("cb", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(1f)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        circuitBreaker.transitionToOpenState();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), circuitBreaker);

        // Act / Assert
        AuthorizationRequest request = SwitchFixtures.request(SwitchFixtures.PAN);
        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
    }

    @Test
    @DisplayName("rollback: APPROVED от сервиса возвращается клиенту")
    void shouldReturnRollbackResponseWhenRollbackSucceeds() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), CircuitBreaker.ofDefaults("cb"));
        server.expect(requestTo(ROLLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(ROLLBACK_APPROVED_JSON, MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = client.rollback(
                SwitchFixtures.request(SwitchFixtures.PAN), "625001000123");

        // Assert
        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
        server.verify();
    }

    @Test
    @DisplayName("rollback: 5xx -> null, исключение не выходит наружу")
    void shouldReturnNullWhenRollbackFails() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), CircuitBreaker.ofDefaults("cb"));
        server.expect(requestTo(ROLLBACK_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Act
        RollbackResponse response = client.rollback(
                SwitchFixtures.request(SwitchFixtures.PAN), "625001000123");

        // Assert
        assertThat(response).isNull();
    }

    @Test
    @DisplayName("rollback: невалидный статус ответа -> null")
    void shouldReturnNullWhenRollbackStatusIsInvalid() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), CircuitBreaker.ofDefaults("cb"));
        server.expect(requestTo(ROLLBACK_URL))
                .andRespond(withSuccess(INVALID_STATUS_JSON, MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = client.rollback(
                SwitchFixtures.request(SwitchFixtures.PAN), "625001000123");

        // Assert
        assertThat(response).isNull();
    }

    @Test
    @DisplayName("checkHealth: сервис отвечает -> ok")
    void shouldReturnOkWhenHealthEndpointResponds() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), CircuitBreaker.ofDefaults("cb"));
        server.expect(requestTo(HEALTH_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess());

        // Act
        String health = client.checkHealth();

        // Assert
        assertThat(health).isEqualTo("ok");
    }

    @Test
    @DisplayName("checkHealth: сервис недоступен -> down")
    void shouldReturnDownWhenHealthEndpointFails() {
        // Arrange
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthorizationClient client = new AuthorizationClient(
                SwitchFixtures.properties(), builder.build(), retry(), CircuitBreaker.ofDefaults("cb"));
        server.expect(requestTo(HEALTH_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Act
        String health = client.checkHealth();

        // Assert
        assertThat(health).isEqualTo("down");
    }

    private Retry retry() {
        return RetryFactory.authorizationRetry(SwitchFixtures.propertiesWithBackoff(List.of(1L, 1L)));
    }
}
