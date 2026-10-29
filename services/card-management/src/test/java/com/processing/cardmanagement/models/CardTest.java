package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты доменной модели {@link Card}: удержание средств, удаление,
 * изменение лимитов. Без Spring-контекста и моков — только правила домена.
 */
class CardTest {

    private static final String PAN = "4000001234567890";

    private static final String RRN = "625001000123";

    @Test
    @DisplayName("startReservation: активная карта и достаточный баланс -> удержание создано")
    void shouldCreateReservationWhenCardIsActiveAndHasFunds() {
        // Arrange
        Card card = card(CardStatus.ACTIVE, "1000");

        // Act
        Reservation reservation = card.startReservation(new BigDecimal("300"), RRN);

        // Assert
        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo("300");
        assertThat(reservation.rrn()).isEqualTo(RRN);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("startReservation: баланс меньше суммы -> InsufficientFundsException")
    void shouldThrowInsufficientFundsWhenBalanceIsTooLow() {
        // Arrange
        Card card = card(CardStatus.ACTIVE, "100");

        // Act / Assert
        assertThatThrownBy(() -> card.startReservation(new BigDecimal("300"), RRN))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    @DisplayName("startReservation: карта не активна -> IllegalStateException со статусом")
    void shouldThrowWhenCardIsNotActive() {
        // Arrange
        Card card = card(CardStatus.BLOCKED, "1000");

        // Act / Assert
        assertThatThrownBy(() -> card.startReservation(new BigDecimal("100"), RRN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BLOCKED");
    }

    @Test
    @DisplayName("withReservation: баланс уменьшается на сумму удержания")
    void shouldDecreaseBalanceWhenReservationApplied() {
        // Arrange
        Card card = card(CardStatus.ACTIVE, "1000");
        Reservation reservation = card.startReservation(new BigDecimal("250"), RRN);

        // Act
        Card reserved = card.withReservation(reservation);

        // Assert
        assertThat(reserved.availableBalance()).isEqualByComparingTo("750");
        assertThat(reserved.pan()).isEqualTo(PAN);
    }

    @Test
    @DisplayName("withReservation: PAN удержания не совпадает с PAN карты -> IllegalArgumentException")
    void shouldThrowWhenReservationPanDiffers() {
        // Arrange
        Card card = card(CardStatus.ACTIVE, "1000");
        Reservation reservation = new Reservation("4000009999999999", new BigDecimal("10"), RRN);

        // Act / Assert
        assertThatThrownBy(() -> card.withReservation(reservation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
    }

    @Test
    @DisplayName("withRollback: баланс увеличивается на сумму возврата")
    void shouldIncreaseBalanceWhenRollbackApplied() {
        // Arrange
        Card card = card(CardStatus.ACTIVE, "700");
        Reservation reservation = new Reservation(PAN, new BigDecimal("300"), RRN);
        ReservationRollback rollback = reservation.startRollback(new BigDecimal("300"));

        // Act
        Card restored = card.withRollback(rollback);

        // Assert
        assertThat(restored.availableBalance()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("withRollback: PAN возврата не совпадает с PAN карты -> IllegalArgumentException")
    void shouldThrowWhenRollbackPanDiffers() {
        // Arrange
        Card card = card(CardStatus.ACTIVE, "700");
        ReservationRollback rollback = new ReservationRollback(
                UUID.randomUUID(), UUID.randomUUID(), "4000009999999999",
                new BigDecimal("300"), RRN, Instant.now());

        // Act / Assert
        assertThatThrownBy(() -> card.withRollback(rollback))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
    }

    @Test
    @DisplayName("deleted: активная карта получает статус DELETED")
    void shouldMarkCardAsDeleted() {
        // Act
        Card deleted = card(CardStatus.ACTIVE, "1000").deleted();

        // Assert
        assertThat(deleted.status()).isEqualTo(CardStatus.DELETED);
        assertThat(deleted.pan()).isEqualTo(PAN);
    }

    @Test
    @DisplayName("deleted: повторное удаление -> IllegalStateException")
    void shouldThrowWhenCardIsAlreadyDeleted() {
        // Arrange
        Card deleted = card(CardStatus.DELETED, "1000");

        // Act / Assert
        assertThatThrownBy(deleted::deleted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already deleted");
    }

    @Test
    @DisplayName("withData: корректные лимиты применяются, остальные поля сохраняются")
    void shouldApplyNewDataWhenLimitsAreValid() {
        // Act
        Card updated = card(CardStatus.ACTIVE, "1000").withData(
                CardStatus.BLOCKED, new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("500"));

        // Assert
        assertThat(updated.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(updated.dailyLimit()).isEqualByComparingTo("100");
        assertThat(updated.monthlyLimit()).isEqualByComparingTo("1000");
        assertThat(updated.availableBalance()).isEqualByComparingTo("500");
        assertThat(updated.pan()).isEqualTo(PAN);
    }

    @Test
    @DisplayName("withData: отрицательный лимит -> IllegalArgumentException")
    void shouldThrowWhenLimitIsNegative() {
        // Act / Assert
        assertThatThrownBy(() -> card(CardStatus.ACTIVE, "1000").withData(
                CardStatus.ACTIVE, new BigDecimal("-1"), new BigDecimal("1000"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("withData: дневной лимит больше месячного -> IllegalArgumentException")
    void shouldThrowWhenDailyLimitExceedsMonthly() {
        // Act / Assert
        assertThatThrownBy(() -> card(CardStatus.ACTIVE, "1000").withData(
                CardStatus.ACTIVE, new BigDecimal("2000"), new BigDecimal("1000"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("larger than monthly");
    }

    @Test
    @DisplayName("fromDraft: карта собирается из черновика с заданным PAN и issuerId")
    void shouldBuildCardFromDraft() {
        // Arrange
        CardDraft draft = new CardDraft(
                "400000", "IVAN IVANOV", CardStatus.ACTIVE, "643",
                new BigDecimal("5000"), new BigDecimal("50000"), new BigDecimal("1000"));

        // Act
        Card card = Card.fromDraft("4000009999999999", "ISS001", 3, draft);

        // Assert
        assertThat(card.pan()).isEqualTo("4000009999999999");
        assertThat(card.issuerId()).isEqualTo("ISS001");
        assertThat(card.bin()).isEqualTo("400000");
        assertThat(card.availableBalance()).isEqualByComparingTo("1000");
        assertThat(card.expiryDate()).isEqualTo(YearMonth.now().plusYears(3));
    }

    @Test
    @DisplayName("copyWithPan: новый PAN и новый идентификатор, остальные поля те же")
    void shouldCopyCardWithNewPan() {
        // Arrange
        Card card = card(CardStatus.ACTIVE, "1000");

        // Act
        Card copy = card.copyWithPan("4000007777777777");

        // Assert
        assertThat(copy.pan()).isEqualTo("4000007777777777");
        assertThat(copy.id()).isNotEqualTo(card.id());
        assertThat(copy.availableBalance()).isEqualByComparingTo("1000");
        assertThat(copy.bin()).isEqualTo(card.bin());
    }

    private Card card(CardStatus status, String balance) {
        return new Card(
                UUID.randomUUID(),
                PAN,
                "400000",
                "IVAN IVANOV",
                YearMonth.now().plusYears(3),
                status,
                "643",
                new BigDecimal("5000"),
                new BigDecimal("50000"),
                new BigDecimal(balance),
                "ISS001");
    }
}
