package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEvent;
import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link CardServiceImpl} на Mockito: все зависимости заменены моками,
 * Spring-контекст и база данных не поднимаются.
 */
@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {

    private static final String PAN = "4000001234567890";

    private static final String NEW_PAN = "4000009999999999";

    private static final String BIN = "400000";

    private static final String ISSUER_ID = "ISS001";

    private static final String RRN = "625001000123";

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRollbackRepository reservationRollbackRepository;

    @Mock
    private CardServiceSettings settings;

    @Mock
    private CardServiceDefaults defaults;

    @Mock
    private PanGenerator panGenerator;

    @Mock
    private CardEventNotifier eventNotifier;

    @Mock
    private BinIssuerService binIssuerService;

    @Mock
    private RetryService retryService;

    @Mock
    private TransactionRunner transactionRunner;

    private CardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(
                cardRepository,
                reservationRepository,
                reservationRollbackRepository,
                settings,
                defaults,
                panGenerator,
                eventNotifier,
                binIssuerService,
                retryService,
                transactionRunner);

        lenient().when(retryService.supply(anyInt(), any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        lenient().when(transactionRunner.runSupplier(any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });
        lenient().doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(transactionRunner).run(any());
    }

    @Test
    @DisplayName("createCard: PAN генерируется, карта сохраняется, событие отправлено")
    void shouldCreateCardWithGeneratedPan() {
        // Arrange
        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(settings.maxCardCreationRetries()).thenReturn(5);
        when(panGenerator.generatePan(BIN)).thenReturn(NEW_PAN);
        when(cardRepository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card created = service.createCard(BIN, "IVAN IVANOV", "643",
                money("5000"), money("50000"), money("1000"));

        // Assert
        assertThat(created.pan()).isEqualTo(NEW_PAN);
        assertThat(created.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(created.availableBalance()).isEqualByComparingTo("1000");
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("getCard: карта не найдена -> CardNotFoundException")
    void shouldThrowWhenCardIsNotFound() {
        // Arrange
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.getCard(PAN))
                .isInstanceOf(CardNotFoundException.class);
    }

    @Test
    @DisplayName("getCards: limit больше максимального -> TooLargeLimitException")
    void shouldThrowWhenPageLimitIsTooLarge() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);

        // Act / Assert
        assertThatThrownBy(() -> service.getCards(101, 0L, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class);
    }

    @Test
    @DisplayName("getCards: limit и offset не заданы -> подставляются значения по умолчанию")
    void shouldUseDefaultsWhenPaginationIsNotProvided() {
        // Arrange
        // settings.maxPageLimit() не стабим: в CardServiceImpl он читается только
        // когда limit != null, а здесь limit null — иначе Mockito упадёт на
        // UnnecessaryStubbing.
        when(defaults.pageLimit()).thenReturn(20);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cardRepository.findCards(20, 0L, null, null, null, null, null))
                .thenReturn(List.of(activeCard()));

        // Act
        List<Card> cards = service.getCards(null, null, null, null, null, null, null);

        // Assert
        assertThat(cards).hasSize(1);
    }

    @Test
    @DisplayName("patchCard: переданные поля заменяются, остальные берутся из карты")
    void shouldPatchOnlyProvidedFields() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card patched = service.patchCard(PAN, CardStatus.BLOCKED, null, null, money("500"));

        // Assert
        assertThat(patched.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(patched.dailyLimit()).isEqualByComparingTo("5000");
        assertThat(patched.availableBalance()).isEqualByComparingTo("500");
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("patchCard: карта не найдена -> CardNotFoundException")
    void shouldThrowWhenPatchedCardIsNotFound() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.patchCard(PAN, null, null, null, null))
                .isInstanceOf(CardNotFoundException.class);
    }

    @Test
    @DisplayName("deleteCard: карта получает статус DELETED")
    void shouldMarkCardAsDeleted() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));

        // Act
        service.deleteCard(PAN);

        // Assert
        ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(CardStatus.DELETED);
    }

    @Test
    @DisplayName("reserve: успешное удержание уменьшает баланс и сохраняет резерв")
    void shouldDecreaseBalanceWhenReservationSucceeds() {
        // Arrange
        Card card = activeCard();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card reserved = service.reserve(PAN, money("300"), RRN);

        // Assert
        assertThat(reserved.availableBalance()).isEqualByComparingTo("700");
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("reserve: RRN уже использован -> ReservationAlreadyExistsException")
    void shouldThrowWhenReservationAlreadyExists() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, money("300"), RRN))
                .isInstanceOf(ReservationAlreadyExistsException.class);
    }

    @Test
    @DisplayName("rollback: удержание не найдено -> ReservationNotFoundException")
    void shouldThrowWhenReservationIsNotFound() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.rollback(PAN, money("300"), RRN))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    @DisplayName("rollback: успешный возврат увеличивает баланс")
    void shouldIncreaseBalanceWhenRollbackSucceeds() {
        // Arrange
        Card card = activeCard();
        Reservation reservation = new Reservation(PAN, money("300"), RRN);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(reservationRollbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card updated = service.rollback(PAN, money("300"), RRN);

        // Assert
        assertThat(updated.availableBalance()).isEqualByComparingTo("1300");
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    @DisplayName("bulkUpdateStatus: не переданы ни BIN, ни PAN -> IllegalArgumentException")
    void shouldThrowWhenBulkStatusHasNoTargets() {
        // Act / Assert
        assertThatThrownBy(() -> service.bulkUpdateStatus(null, null, CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Either bin or pans");
    }

    @Test
    @DisplayName("bulkUpdateStatus: переданы и BIN, и PAN -> IllegalArgumentException")
    void shouldThrowWhenBulkStatusHasBothTargets() {
        // Act / Assert
        assertThatThrownBy(() -> service.bulkUpdateStatus(
                List.of(BIN), List.of(PAN), CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only one of bin or pans");
    }

    @Test
    @DisplayName("bulkUpdateStatus: массовое изменение по PAN возвращает количество карт")
    void shouldUpdateStatusForAllPans() {
        // Arrange
        when(cardRepository.findCardsByPansForUpdate(List.of(PAN))).thenReturn(List.of(activeCard()));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        int updated = service.bulkUpdateStatus(null, List.of(PAN), CardStatus.BLOCKED);

        // Assert
        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(CardStatus.BLOCKED);
    }

    @Test
    @DisplayName("countAllCardsAndUpdate: значение из репозитория уходит в событие")
    void shouldNotifyWithTotalCardsAmount() {
        // Arrange
        when(cardRepository.countAllCards()).thenReturn(42L);

        // Act
        long count = service.countAllCardsAndUpdate();

        // Assert
        assertThat(count).isEqualTo(42L);
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    private Card activeCard() {
        return new Card(
                UUID.randomUUID(),
                PAN,
                BIN,
                "IVAN IVANOV",
                YearMonth.now().plusYears(3),
                CardStatus.ACTIVE,
                "643",
                money("5000"),
                money("50000"),
                money("1000"),
                ISSUER_ID,
                Instant.now());
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
