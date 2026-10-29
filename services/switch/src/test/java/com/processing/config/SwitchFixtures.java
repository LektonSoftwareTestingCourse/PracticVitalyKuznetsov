package com.processing.config;

import com.processing.common.dto.authorization.AuthorizationRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Object Mother для тестов Switch: собирает конфигурацию и DTO запроса,
 * чтобы Arrange в тестах читался как сценарий.
 */
public final class SwitchFixtures {

    public static final String PAN = "4000001234567890";

    public static final String BIN = "400000";

    public static final String ISSUER_ID = "ISS001";

    public static final String TERMINAL_ID = "TERM0001";

    public static final String MERCHANT_ID = "MERCH0000000002";

    public static final String STAN = "000001";

    public static final BigDecimal AMOUNT = new BigDecimal("1000");

    public static final Instant REQUEST_TIME = Instant.now();

    private SwitchFixtures() {
    }

    /**
     * @return конфигурация Switch с BIN {@value #BIN} и backoff по умолчанию
     */
    public static SwitchProperties properties() {
        return properties(Map.of(BIN, ISSUER_ID), List.of(1L, 2L));
    }

    /**
     * @param binRouting таблица BIN → issuerId
     * @return конфигурация Switch с заданной таблицей маршрутизации
     */
    public static SwitchProperties properties(Map<String, String> binRouting) {
        return properties(binRouting, List.of(1L, 2L));
    }

    /**
     * @param backoffMs интервалы между попытками
     * @return конфигурация Switch с заданным backoff
     */
    public static SwitchProperties propertiesWithBackoff(List<Long> backoffMs) {
        return properties(Map.of(BIN, ISSUER_ID), backoffMs);
    }

    /**
     * @param binRouting таблица BIN → issuerId
     * @param backoffMs  интервалы между попытками
     * @return полностью заполненная конфигурация Switch
     */
    public static SwitchProperties properties(Map<String, String> binRouting, List<Long> backoffMs) {
        return new SwitchProperties(
                "1.0.0",
                binRouting,
                "http://localhost:8083",
                "http://localhost:8088",
                "http://localhost:8084",
                new SwitchProperties.HttpProperties(1000, 2000, 2000),
                new SwitchProperties.RetryProperties(3, backoffMs),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 1000L)));
    }

    /**
     * @param pan номер карты
     * @return валидный запрос авторизации с заданным PAN
     */
    public static AuthorizationRequest request(String pan) {
        return new AuthorizationRequest(
                "0100", STAN, pan, "000000", AMOUNT, "643", REQUEST_TIME,
                TERMINAL_ID, "POS", MERCHANT_ID, "5045", "ACQ002", ISSUER_ID);
    }
}
