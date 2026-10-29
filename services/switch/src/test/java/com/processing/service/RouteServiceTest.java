package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.config.SwitchFixtures;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link RouteService}: маршрутизация, деградация при ошибках,
 * логирование и rollback при недоступности очереди.
 */
@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String RRN = "625001000123";

    @Mock
    private RoutingService routingService;

    @Mock
    private AuthorizationClient authorizationClient;

    @Mock
    private AcquiringFeeClient acquiringFeeClient;

    @Mock
    private LoggerClient loggerClient;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    @DisplayName("Успешный маршрут: issuerId подставлен, комиссия получена, транзакция залогирована")
    void shouldReturnAuthorizationResponseWhenRoutingSucceeds() {
        // Arrange
        AuthorizationRequest request = requestWithoutIssuer();
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN)).thenReturn(SwitchFixtures.ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new BigDecimal("25.00"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        ArgumentCaptor<AuthorizationRequest> captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationClient).authorize(captor.capture());
        assertThat(captor.getValue().issuerId()).isEqualTo(SwitchFixtures.ISSUER_ID);
        verify(loggerClient).log(any(TransactionRequest.class));
    }

    @Test
    @DisplayName("Неизвестный BIN -> DECLINED/CARD_NOT_FOUND без вызова Authorization")
    void shouldDeclineWithCardNotFoundWhenBinIsUnknown() {
        // Arrange
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN))
                .thenThrow(new UnknownBinException(SwitchFixtures.BIN));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(SwitchFixtures.request(SwitchFixtures.PAN));

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        verifyNoInteractions(authorizationClient);
    }

    @Test
    @DisplayName("PAN = null -> маршрутизация по маркеру ???? и отказ CARD_NOT_FOUND")
    void shouldDeclineWithCardNotFoundWhenPanIsNull() {
        // Arrange
        when(routingService.getIssuerIdByPan(null)).thenThrow(new UnknownBinException("null-or-short"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(SwitchFixtures.request(null));

        // Assert
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        verifyNoInteractions(authorizationClient);
    }

    @Test
    @DisplayName("PAN короче 6 символов -> отказ CARD_NOT_FOUND")
    void shouldDeclineWithCardNotFoundWhenPanIsShort() {
        // Arrange
        when(routingService.getIssuerIdByPan("4000")).thenThrow(new UnknownBinException("null-or-short"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(SwitchFixtures.request("4000"));

        // Assert
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        verifyNoInteractions(authorizationClient);
    }

    @Test
    @DisplayName("Authorization недоступен -> DECLINED со статусом SERVICE_UNAVAILABLE")
    void shouldDeclineWithServiceUnavailableWhenAuthorizationFails() {
        // Arrange
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN)).thenReturn(SwitchFixtures.ISSUER_ID);
        when(authorizationClient.authorize(any()))
                .thenThrow(new AuthorizationException(SwitchFixtures.STAN, 3, "connection refused"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(SwitchFixtures.request(SwitchFixtures.PAN));

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_DECLINED_GENERAL);
        assertThat(response.declineReason()).isEqualTo("Authorization service unavailable");
    }

    @Test
    @DisplayName("Логирование упало после APPROVED -> rollback и systemError (96)")
    void shouldRollbackAndReturnSystemErrorWhenLoggingFailsAfterApproval() {
        // Arrange
        AuthorizationRequest request = requestWithoutIssuer();
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN)).thenReturn(SwitchFixtures.ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(null);
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), eq(RRN))).thenReturn(null);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        assertThat(response.declineReason()).isEqualTo("System error");
        verify(authorizationClient).rollback(any(), eq(RRN));
    }

    @Test
    @DisplayName("Rollback подтверждён Authorization -> systemError")
    void shouldReturnSystemErrorWhenRollbackIsApproved() {
        // Arrange
        AuthorizationRequest request = requestWithoutIssuer();
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN)).thenReturn(SwitchFixtures.ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), anyString()))
                .thenReturn(RollbackResponse.approved(rollbackRequest(), SwitchFixtures.REQUEST_TIME));

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        verify(authorizationClient).rollback(any(), eq(RRN));
    }

    @Test
    @DisplayName("Rollback отклонён Authorization -> systemError")
    void shouldReturnSystemErrorWhenRollbackIsDeclined() {
        // Arrange
        AuthorizationRequest request = requestWithoutIssuer();
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN)).thenReturn(SwitchFixtures.ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), anyString()))
                .thenReturn(RollbackResponse.declined(
                        rollbackRequest(), "ALREADY_ROLLED_BACK", "05", SwitchFixtures.REQUEST_TIME));

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        verify(authorizationClient).rollback(any(), eq(RRN));
    }

    @Test
    @DisplayName("Логирование упало для DECLINED -> rollback не выполняется")
    void shouldNotRollbackWhenLoggingFailsForDeclinedTransaction() {
        // Arrange
        AuthorizationRequest request = requestWithoutIssuer();
        when(routingService.getIssuerIdByPan(SwitchFixtures.PAN)).thenReturn(SwitchFixtures.ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(declinedResponse());
        when(loggerClient.log(any())).thenReturn(false);

        // Act
        AuthorizationResponse response = routeService.route(request);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_EXPIRED);
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    private AuthorizationRequest requestWithoutIssuer() {
        return SwitchFixtures.request(SwitchFixtures.PAN).withIssuerId(null);
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest(RRN, SwitchFixtures.PAN, SwitchFixtures.AMOUNT);
    }

    private AuthorizationResponse approvedResponse() {
        return new AuthorizationResponse("0100", SwitchFixtures.STAN, RRN, "AB12CD",
                AuthorizationResponse.CODE_APPROVED, AuthorizationResponse.STATUS_APPROVED, null, 10);
    }

    private AuthorizationResponse declinedResponse() {
        return new AuthorizationResponse("0100", SwitchFixtures.STAN, null, null,
                AuthorizationResponse.CODE_CARD_EXPIRED, AuthorizationResponse.STATUS_DECLINED,
                "CARD_EXPIRED", null);
    }
}
