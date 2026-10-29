package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.config.SwitchFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты пакетно-приватного {@link AuthorizationRequestNormalizer}:
 * нормализация terminalId и merchantId до форматов, ожидаемых Authorization.
 */
class AuthorizationRequestNormalizerTest {

    @ParameterizedTest(name = "terminalId=[{0}] -> [{1}]")
    @CsvSource({
            "'   ', '   '",
            "TERM0001, TERM0001",
            "TERM00012, TERM0001",
            "TERM, TERM0000",
            "-1, -1000000"
    })
    @DisplayName("terminalId дополняется нулями или обрезается до 8 символов")
    void shouldNormalizeTerminalId(String terminalId, String expected) {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(terminalId);

        // Assert
        assertThat(normalized).isEqualTo(expected);
    }

    @Test
    @DisplayName("Пустой terminalId возвращается без изменений")
    void shouldKeepTerminalIdWhenItIsNullOrBlank() {
        // Act
        String nullId = AuthorizationRequestNormalizer.normalizeTerminalId(null);
        String blankId = AuthorizationRequestNormalizer.normalizeTerminalId("");

        // Assert
        assertThat(nullId).isNull();
        assertThat(blankId).isEmpty();
    }

    @ParameterizedTest(name = "merchantId=[{0}] -> [{1}]")
    @CsvSource({
            "MERCH0000000002, MERCH0000000002",
            "MERCH00000000002, MERCH0000000002",
            "MERCHX0000000002, MERCHX000000000",
            "MERCHANT123456789, MERCHANT1234567",
            "MERCH, MERCH0000000000"
    })
    @DisplayName("merchantId приводится к 15 символам")
    void shouldNormalizeMerchantId(String merchantId, String expected) {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(merchantId);

        // Assert
        assertThat(normalized).isEqualTo(expected);
    }

    @Test
    @DisplayName("Пустой merchantId возвращается без изменений")
    void shouldKeepMerchantIdWhenItIsNullOrBlank() {
        // Act
        String nullId = AuthorizationRequestNormalizer.normalizeMerchantId(null);
        String blankId = AuthorizationRequestNormalizer.normalizeMerchantId("  ");

        // Assert
        assertThat(nullId).isNull();
        assertThat(blankId).isEqualTo("  ");
    }

    @Test
    @DisplayName("normalize заменяет только терминал и мерчанта, остальные поля сохраняются")
    void shouldKeepOtherFieldsWhenNormalizing() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "000001", SwitchFixtures.PAN, "000000", SwitchFixtures.AMOUNT,
                "643", SwitchFixtures.REQUEST_TIME, "T1", "POS", "M1",
                "5045", "ACQ002", SwitchFixtures.ISSUER_ID);

        // Act
        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(request);

        // Assert
        assertThat(normalized.terminalId()).isEqualTo("T1000000");
        assertThat(normalized.merchantId()).isEqualTo("M10000000000000");
        assertThat(normalized.pan()).isEqualTo(request.pan());
        assertThat(normalized.stan()).isEqualTo(request.stan());
        assertThat(normalized.amount()).isEqualTo(request.amount());
        assertThat(normalized.issuerId()).isEqualTo(SwitchFixtures.ISSUER_ID);
    }
}
