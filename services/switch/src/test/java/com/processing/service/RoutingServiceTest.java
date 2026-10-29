package com.processing.service;

import com.processing.config.SwitchFixtures;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты {@link RoutingService}: поиск issuerId по BIN и классы
 * некорректного PAN.
 */
class RoutingServiceTest {

    private final RoutingService routingService = new RoutingService(SwitchFixtures.properties());

    @Test
    @DisplayName("Известный BIN -> issuerId из таблицы маршрутизации")
    void shouldReturnIssuerIdWhenBinIsKnown() {
        // Act
        String issuerId = routingService.getIssuerIdByPan(SwitchFixtures.PAN);

        // Assert
        assertThat(issuerId).isEqualTo(SwitchFixtures.ISSUER_ID);
    }

    @ParameterizedTest(name = "pan=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "4000", "40000"})
    @DisplayName("PAN короче 6 символов -> UnknownBinException")
    void shouldThrowWhenPanIsNullOrShort(String pan) {
        // Act / Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("null-or-short");
    }

    @Test
    @DisplayName("Неизвестный BIN -> UnknownBinException с BIN в сообщении")
    void shouldThrowWhenBinIsUnknown() {
        // Act / Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan("5555551234567890"))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("555555");
    }
}
