package com.processing.controller;

import com.processing.config.SwitchFixtures;
import com.processing.dto.HealthResponse;
import com.processing.service.AuthorizationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link HealthController}: агрегация статуса Switch и Authorization.
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private AuthorizationClient authorizationClient;

    private HealthController healthController;

    @BeforeEach
    void setUp() {
        healthController = new HealthController(SwitchFixtures.properties(), authorizationClient);
    }

    @Test
    @DisplayName("Authorization доступен -> dependency = ok")
    void shouldReportOkWhenAuthorizationIsUp() {
        // Arrange
        when(authorizationClient.checkHealth()).thenReturn("ok");

        // Act
        ResponseEntity<HealthResponse> response = healthController.health();

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("ok");
        assertThat(response.getBody().service()).isEqualTo("switch");
        assertThat(response.getBody().version()).isEqualTo("1.0.0");
        assertThat(response.getBody().dependencies()).containsEntry("authorization", "ok");
    }

    @Test
    @DisplayName("Authorization недоступен -> dependency = down, сервис остаётся ok")
    void shouldReportDownWhenAuthorizationIsDown() {
        // Arrange
        when(authorizationClient.checkHealth()).thenReturn("down");

        // Act
        ResponseEntity<HealthResponse> response = healthController.health();

        // Assert
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().dependencies()).containsEntry("authorization", "down");
    }
}
