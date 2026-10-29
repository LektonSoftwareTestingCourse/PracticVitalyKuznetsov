package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthDeclineNoCardEvent;
import com.processing.authorization.events.AuthServiceAuthDeclineNoServiceEvent;
import com.processing.authorization.events.AuthServiceAuthDeclineUnknownEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedCardExpiryDateEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedCardStatusEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedFundsEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedLimitsEvent;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceDuplicateKeyEvent;
import com.processing.authorization.events.AuthServiceRollbackDeclineConflictEvent;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidGetCardRequestException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Полный набор unit-тестов на {@link AuthServiceImpl}: покрывает happy path,
 * все ветки обработки ошибок Card Management, правила статуса/срока/лимитов
 * и обработку rollback.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final Instant REQUEST_TIME = Instant.now();
    private static final String RRN = "625001000123";
    private static final long RRN_BASE = 7L;

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                limitUsageRepository,
                eventNotifier,
                binLookupClient,
                cardManagementClient);
    }

    @Test
    @DisplayName("Активная карта, средств достаточно, лимит обновлён -> APPROVED")
    void shouldApproveAuthorizationWhenCardIsActiveAndSufficientFunds() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(RRN_BASE);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_APPROVED);
        assertThat(response.declineReason()).isNull();
        assertThat(response.rrn()).hasSize(12);
        assertThat(response.authCode()).matches("[0-9A-Z]{6}");
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
        verify(eventNotifier).notify(any(AuthServiceAuthorizeEvent.class));
    }

    @Test
    @DisplayName("BIN lookup вернул issuerId -> транзакция всё равно APPROVED")
    void shouldApproveAuthorizationWhenBinLookupReturnsIssuerId() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
    }

    @Test
    @DisplayName("CardNotFoundException при получении карты -> DECLINED/CARD_NOT_FOUND")
    void shouldDeclineWhenCardNotFound() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        verify(eventNotifier).notify(any(AuthServiceAuthDeclineNoCardEvent.class));
    }

    @Test
    @DisplayName("Некорректный запрос в Card Management -> DECLINED/CARD_NOT_FOUND")
    void shouldDeclineWhenCardRequestIsInvalid() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new InvalidGetCardRequestException(PAN));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        verify(eventNotifier).notify(any(AuthServiceAuthDeclineNoCardEvent.class));
    }

    @ParameterizedTest(name = "{0} -> SERVICE_UNAVAILABLE")
    @MethodSource("cardManagementUnavailableFailures")
    @DisplayName("Недоступность Card Management -> DECLINED/SERVICE_UNAVAILABLE")
    void shouldDeclineWithServiceUnavailableWhenCardManagementIsDown(RuntimeException failure) {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(failure);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        verify(eventNotifier).notify(any(AuthServiceAuthDeclineNoServiceEvent.class));
    }

    @Test
    @DisplayName("PaymentRequiredException -> DECLINED/CARD_BLOCKED")
    void shouldDeclineWithCardBlockedWhenCardManagementRequiresPayment() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new PaymentRequiredException("payment required"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("CARD_BLOCKED");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_DECLINED_GENERAL);
        verify(eventNotifier).notify(any(AuthServiceAuthDeclinedCardStatusEvent.class));
    }

    @Test
    @DisplayName("GetCardException -> DECLINED/SERVICE_UNAVAILABLE")
    void shouldDeclineWithServiceUnavailableWhenGetCardFails() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new GetCardException("cms failure"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
        verify(eventNotifier).notify(any(AuthServiceAuthDeclineNoServiceEvent.class));
    }

    @Test
    @DisplayName("Непредвиденная ошибка Card Management -> DECLINED/UNKNOWN_REASON")
    void shouldDeclineWithUnknownReasonWhenUnexpectedErrorOccurs() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new IllegalStateException("boom"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_DECLINED_GENERAL);
        verify(eventNotifier).notify(any(AuthServiceAuthDeclineUnknownEvent.class));
    }

    @Test
    @DisplayName("Card Management вернул карту без статуса -> DECLINED/UNKNOWN_REASON")
    void shouldDeclineWithUnknownReasonWhenCardStatusIsNull() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(null));
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        verify(eventNotifier).notify(any(AuthServiceAuthDeclineUnknownEvent.class));
    }

    @ParameterizedTest(name = "status={0} -> {1}/{2}")
    @CsvSource({
            "EXPIRED,  CARD_EXPIRED,  54",
            "BLOCKED,  CARD_BLOCKED,  05",
            "INACTIVE, CARD_INACTIVE, 05"
    })
    @DisplayName("Неактивный статус карты отображается в конкретную причину отказа")
    void shouldMapCardStatusToDeclineOutcome(CardModelStatus status, String reason, String code) {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(status));
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
        verify(eventNotifier).notify(any(AuthServiceAuthDeclinedCardStatusEvent.class));
    }

    @Test
    @DisplayName("Срок действия карты истёк -> DECLINED/CARD_EXPIRED")
    void shouldDeclineWithCardExpiredWhenExpiryDateIsInThePast() {
        // Arrange
        when(cardManagementClient.getCard(PAN))
                .thenReturn(cardWithExpiry(YearMonth.now(ZoneOffset.UTC).minusYears(1)));
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_EXPIRED);
        verify(eventNotifier).notify(any(AuthServiceAuthDeclinedCardExpiryDateEvent.class));
    }

    @Test
    @DisplayName("Срок действия карты истекает в текущем месяце -> карта ещё валидна")
    void shouldApproveWhenExpiryDateIsCurrentMonth() {
        // Arrange
        when(cardManagementClient.getCard(PAN))
                .thenReturn(cardWithExpiry(YearMonth.now(ZoneOffset.UTC)));
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
    }

    @Test
    @DisplayName("Сумма больше доступного баланса -> DECLINED/INSUFFICIENT_FUNDS")
    void shouldDeclineWithInsufficientFundsWhenAmountExceedsBalance() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(cardWithBalance(new BigDecimal("500")));
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_INSUFFICIENT_FUNDS);
        verify(eventNotifier).notify(any(AuthServiceAuthDeclinedFundsEvent.class));
    }

    @Test
    @DisplayName("Лимиты не обновлены (0 строк) -> DECLINED/EXCEEDS_AMOUNT_LIMIT")
    void shouldDeclineWithExceedsLimitWhenLimitsAreNotUpdated() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_EXCEEDS_LIMIT);
        verify(eventNotifier).notify(any(AuthServiceAuthDeclinedLimitsEvent.class));
    }

    @Test
    @DisplayName("Конфликт ключа при обновлении лимитов -> повтор, затем APPROVED")
    void shouldRetryLimitUpdateWhenDuplicateKeyOccurs() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenReturn(1);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        verify(eventNotifier).notify(any(AuthServiceDuplicateKeyEvent.class));
        verify(limitUsageRepository, times(2)).upsertLimitUsage(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Повтор после конфликта ключа падает -> DECLINED/DB_UNAVAILABLE")
    void shouldDeclineWithDbUnavailableWhenRetryAfterDuplicateKeyFails() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenThrow(new IllegalStateException("retry failed"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        verify(eventNotifier).notify(any(AuthServiceDuplicateKeyEvent.class));
        verify(eventNotifier).notify(any(AuthServiceAuthDeclineNoServiceEvent.class));
    }

    @Test
    @DisplayName("Ошибка БД при обновлении лимитов -> DECLINED/DB_UNAVAILABLE")
    void shouldDeclineWithDbUnavailableWhenLimitUpdateFails() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db is down"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest(name = "{0} -> {1}/{2}")
    @MethodSource("reserveFailures")
    @DisplayName("Ошибки резервирования средств отображаются в конкретную причину отказа")
    void shouldMapReserveFailureToDeclineOutcome(RuntimeException failure, String reason, String code) {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        doThrow(failure).when(cardManagementClient).reserve(any(), anyString(), anyString());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    @DisplayName("Успешный rollback -> APPROVED")
    void shouldApproveRollbackWhenCardManagementRollsBack() {
        // Arrange
        RollbackRequest request = rollbackRequest();

        // Act
        RollbackResponse response = authService.rollback(request, REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
        assertThat(response.responseCode()).isEqualTo(RollbackResponse.CODE_SUCCESS);
        verify(cardManagementClient).rollback(request);
        verify(eventNotifier).notify(any(AuthServiceRollbackEvent.class));
    }

    @ParameterizedTest(name = "{0} -> {1}/{2}")
    @MethodSource("rollbackFailures")
    @DisplayName("Ошибки rollback отображаются в конкретную причину отказа")
    void shouldMapRollbackFailureToDeclineOutcome(RuntimeException failure, String reason, String code) {
        // Arrange
        RollbackRequest request = rollbackRequest();
        doThrow(failure).when(cardManagementClient).rollback(request);

        // Act
        RollbackResponse response = authService.rollback(request, REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("Повторный вызов rollback -> DECLINED/ALREADY_ROLLED_BACK")
    void shouldDeclineRollbackWhenAlreadyRolledBack() {
        // Arrange
        RollbackRequest request = rollbackRequest();
        doThrow(new RollbackConflictException(RRN)).when(cardManagementClient).rollback(request);

        // Act
        RollbackResponse response = authService.rollback(request, REQUEST_TIME);

        // Assert
        assertThat(response.declineReason()).isEqualTo("ALREADY_ROLLED_BACK");
        verify(eventNotifier).notify(any(AuthServiceRollbackDeclineConflictEvent.class));
    }

    private static Stream<Arguments> cardManagementUnavailableFailures() {
        return Stream.of(
                Arguments.of(new ServiceUnavailableException("cms is down")),
                Arguments.of(new ResourceAccessException("connection refused")),
                Arguments.of(new InternalCardManagerException("cms internal error"))
        );
    }

    private static Stream<Arguments> reserveFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "CARD_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("cms is down"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new ResourceAccessException("connection refused"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new InvalidReserveRequestException(PAN), "CARD_NOT_FOUND", "14"),
                Arguments.of(new InsufficientFundsException("no funds"), "INSUFFICIENT_FUNDS", "51"),
                Arguments.of(new ReserveException("reserve failed"), "RESERVATION_FAILED", "96"),
                Arguments.of(new IllegalStateException("boom"), "UNKNOWN_REASON", "05")
        );
    }

    private static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(new InvalidRollbackRequestException(RRN), "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("cms is down"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new RollbackConflictException(RRN), "ALREADY_ROLLED_BACK", "05"),
                Arguments.of(new RollbackFailureException("rollback failed"), "ROLLBACK_FAILED", "96"),
                Arguments.of(new IllegalStateException("boom"), "UNKNOWN_REASON", "05")
        );
    }

    private AuthorizationRequest validRequest() {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                AMOUNT, "643", REQUEST_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID
        );
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest(RRN, PAN, AMOUNT);
    }

    private CardModel activeCard() {
        return cardWithStatus(CardModelStatus.ACTIVE);
    }

    private CardModel cardWithBalance(BigDecimal availableBalance) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.now(ZoneOffset.UTC).plusYears(2), CardModelStatus.ACTIVE, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), availableBalance,
                ISSUER_ID, Instant.now()
        );
    }

    private CardModel cardWithExpiry(YearMonth expiryDate) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                expiryDate, CardModelStatus.ACTIVE, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("10000"),
                ISSUER_ID, Instant.now()
        );
    }

    private CardModel cardWithStatus(CardModelStatus status) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.now(ZoneOffset.UTC).plusYears(2), status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("10000"),
                ISSUER_ID, Instant.now()
        );
    }
}
