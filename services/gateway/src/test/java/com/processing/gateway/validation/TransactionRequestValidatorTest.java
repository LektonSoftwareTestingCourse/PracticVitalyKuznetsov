package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты {@link TransactionRequestValidator}: контракт запроса авторизации
 * на входе Gateway.
 */
class TransactionRequestValidatorTest {

    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @Test
    @DisplayName("Корректный запрос проходит валидацию")
    void shouldAcceptValidRequest() {
        // Arrange
        AuthorizationRequest request = builder().build();

        // Act / Assert
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Пустое тело запроса -> Request body is required")
    void shouldRejectNullRequest() {
        // Act / Assert
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    @Test
    @DisplayName("mti не 0100 -> ошибка контракта")
    void shouldRejectNonAuthorizationMti() {
        // Arrange
        AuthorizationRequest request = builder().mti("0200").build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mti' must be '0100'");
    }

    @Test
    @DisplayName("PAN не из 16 цифр -> ошибка контракта")
    void shouldRejectInvalidPan() {
        // Arrange
        AuthorizationRequest request = builder().pan("400000123456789").build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'pan' must be exactly 16 digits");
    }

    @Test
    @DisplayName("Сумма не задана -> ошибка контракта")
    void shouldRejectNullAmount() {
        // Arrange
        AuthorizationRequest request = builder().amount(null).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' is required");
    }

    @Test
    @DisplayName("Сумма равна нулю -> ошибка контракта")
    void shouldRejectNonPositiveAmount() {
        // Arrange
        AuthorizationRequest request = builder().amount(BigDecimal.ZERO).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' must be > 0");
    }

    @Test
    @DisplayName("Код валюты не из 3 символов -> ошибка контракта")
    void shouldRejectInvalidCurrencyCode() {
        // Arrange
        AuthorizationRequest request = builder().currencyCode("64").build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'currencyCode' must be exactly 3 characters");
    }

    @Test
    @DisplayName("MCC не из 4 цифр -> ошибка контракта")
    void shouldRejectInvalidMcc() {
        // Arrange
        AuthorizationRequest request = builder().mcc("50A5").build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mcc' must be exactly 4 digits");
    }

    @Test
    @DisplayName("Пустой обязательный STAN -> ошибка контракта")
    void shouldRejectBlankRequiredField() {
        // Arrange
        AuthorizationRequest request = builder().stan("  ").build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'stan' is required");
    }

    @Test
    @DisplayName("Не задано время передачи -> ошибка контракта")
    void shouldRejectNullTransmissionDateTime() {
        // Arrange
        AuthorizationRequest request = builder().transmissionDateTime(null).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'transmissionDateTime' is required");
    }

    private AuthorizationRequest.AuthorizationRequestBuilder builder() {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan("000001")
                .pan("4000001234567890")
                .processingCode("000000")
                .amount(new BigDecimal("1000"))
                .currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-01-01T10:00:00Z"))
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId("MERCH0000000002")
                .mcc("5045")
                .acquirerId("ACQ002")
                .issuerId("ISS001");
    }
}
