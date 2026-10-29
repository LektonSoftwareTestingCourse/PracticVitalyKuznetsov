package com.processing.gateway.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link LogDataMasker}: маскирование PAN и CVV в логах.
 */
class LogDataMaskerTest {

    private final LogDataMasker masker = new LogDataMasker();

    @Test
    @DisplayName("PAN в JSON маскируется, BIN и последние 4 цифры остаются")
    void shouldMaskPanInJson() {
        // Act
        String masked = masker.maskData("{\"pan\": \"4000001234567890\"}");

        // Assert
        assertThat(masked).contains("400000****7890");
        assertThat(masked).doesNotContain("123456");
    }

    @Test
    @DisplayName("CVV в JSON полностью заменяется звёздочками")
    void shouldMaskCvvInJson() {
        // Act
        String masked = masker.maskData("{\"cvv\": \"123\"}");

        // Assert
        assertThat(masked).isEqualTo("{\"cvv\":\"***\"}");
    }

    @Test
    @DisplayName("Данные без чувствительных полей не меняются")
    void shouldKeepDataWithoutSensitiveFields() {
        // Arrange
        String data = "{\"amount\": 1000, \"currency\": \"643\"}";

        // Act
        String masked = masker.maskData(data);

        // Assert
        assertThat(masked).isEqualTo(data);
    }

    @Test
    @DisplayName("PAN и CVV маскируются в одном сообщении")
    void shouldMaskBothPanAndCvv() {
        // Act
        String masked = masker.maskData("{\"pan\": \"4000001234567890\", \"cvv\": \"999\"}");

        // Assert
        assertThat(masked).contains("400000****7890");
        assertThat(masked).contains("\"cvv\":\"***\"");
    }
}
