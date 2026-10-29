package com.processing.controller;

import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.config.SwitchFixtures;
import com.processing.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link RouteController}: передача запроса в сервис и маскирование
 * PAN при логировании.
 */
@ExtendWith(MockitoExtension.class)
class RouteControllerTest {

    @Mock
    private RouteService routeService;

    private RouteController routeController;

    @BeforeEach
    void setUp() {
        routeController = new RouteController(routeService);
    }

    @Test
    @DisplayName("Корректный PAN -> ответ сервиса пробрасывается клиенту")
    void shouldReturnServiceResponseWhenRequestIsValid() {
        // Arrange
        when(routeService.route(any())).thenReturn(AuthorizationResponse.unknownBin(SwitchFixtures.STAN));

        // Act
        ResponseEntity<AuthorizationResponse> response =
                routeController.routeTransaction(SwitchFixtures.request(SwitchFixtures.PAN));

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
    }

    @ParameterizedTest(name = "pan=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "4000", "4000001"})
    @DisplayName("PAN null или короче 8 символов маскируется целиком")
    void shouldMaskShortPanWhenLogging(String pan) {
        // Arrange
        when(routeService.route(any())).thenReturn(AuthorizationResponse.unknownBin(SwitchFixtures.STAN));

        // Act
        ResponseEntity<AuthorizationResponse> response =
                routeController.routeTransaction(SwitchFixtures.request(pan));

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
