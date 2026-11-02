package org.example.github_api_practice.reservation;

import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.Member;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class ReservationIntegrationTests extends ReservationTestSupport {

    @Test
    void createsConfirmedReservationWithSeoulTimes() {
        var slot = futureSlot();
        var result = service.reserve(OWNER, slot.getId());

        assertThat(result.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(result.startsAt().toString()).isEqualTo("2030-05-01T10:00+09:00");
        var saved = reservations.findById(result.reservationId()).orElseThrow();
        assertThat(saved.getMember().getId()).isEqualTo(owner.getId());
        assertThat(saved.getActiveSlotId()).isEqualTo(slot.getId());
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(service.availableSlots(DAY)).isEmpty();
        assertThat(jdbc.queryForObject("select starts_at from reservation_slots where id = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), slot.getId()).toString())
                .isEqualTo("2030-05-01T01:00");
    }

    @Test
    void rejectsOccupiedSlotForSameAndDifferentMembers() {
        var slot = futureSlot();
        service.reserve(OWNER, slot.getId());
        expectError(() -> service.reserve(OWNER, slot.getId()), ErrorCode.SLOT_ALREADY_RESERVED);
        expectError(() -> service.reserve(OTHER, slot.getId()), ErrorCode.SLOT_ALREADY_RESERVED);
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    void cancellationPreservesHistoryAndAllowsRepeatedRebooking() {
        var slot = futureSlot();
        var first = service.reserve(OWNER, slot.getId());
        var cancelled = service.cancel(OWNER, first.reservationId());
        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelled.cancelledAt().toInstant()).isEqualTo(NOW);
        assertThat(reservations.findById(first.reservationId()).orElseThrow().getActiveSlotId()).isNull();
        assertThat(service.availableSlots(DAY)).extracting("slotId").containsExactly(slot.getId());

        var second = service.reserve(OTHER, slot.getId());
        service.cancel(OTHER, second.reservationId());
        var third = service.reserve(OWNER, slot.getId());
        assertThat(third.reservationId()).isNotEqualTo(first.reservationId());
        assertThat(reservations.count()).isEqualTo(3);
        assertThat(activeCount(slot.getId())).isEqualTo(1);
    }

    @Test
    void repeatedCancellationCannotCancelNewOwnersReservation() {
        var slot = futureSlot();
        var first = service.reserve(OWNER, slot.getId());
        service.cancel(OWNER, first.reservationId());
        var second = service.reserve(OTHER, slot.getId());
        expectError(() -> service.cancel(OWNER, first.reservationId()), ErrorCode.RESERVATION_ALREADY_CANCELLED);
        assertThat(reservations.findById(second.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void otherMemberCannotCancelReservation() {
        var result = service.reserve(OWNER, futureSlot().getId());
        expectError(() -> service.cancel(OTHER, result.reservationId()), ErrorCode.RESERVATION_FORBIDDEN);
        assertThat(reservations.findById(result.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void listContainsOnlyOwnHistoryWithStablePagination() {
        var first = service.reserve(OWNER, slot("2030-05-01T10:00:00+09:00").getId());
        var second = service.reserve(OWNER, slot("2030-05-01T11:00:00+09:00").getId());
        service.cancel(OWNER, first.reservationId());
        service.reserve(OTHER, slot("2030-05-01T12:00:00+09:00").getId());

        var page = service.mine(OWNER, 0, 1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).extracting("reservationId").containsExactly(second.reservationId());
        assertThat(service.mine(OWNER, 1, 1).content()).extracting("status")
                .containsExactly(ReservationStatus.CANCELLED);
        assertThat(service.mine(OWNER, 2, 1).content()).isEmpty();
    }

    @Test
    void seoulDayBoundariesExcludeAdjacentDays() {
        slot("2030-04-30T23:59:59+09:00");
        var midnight = slot("2030-05-01T00:00:00+09:00");
        var last = slot("2030-05-01T23:59:59+09:00");
        slot("2030-05-02T00:00:00+09:00");

        assertThat(service.availableSlots(DAY)).extracting("slotId")
                .containsExactly(midnight.getId(), last.getId());
    }

    @Test
    void availableDatesAreDistinctOrderedAndOnlyIncludeAvailableFutureSlots() {
        slot("2030-04-30T22:00:00+09:00");
        slot("2030-05-01T10:00:00+09:00");
        slot("2030-05-01T11:00:00+09:00");
        var occupied = slot("2030-05-02T10:00:00+09:00");
        service.reserve(OWNER, occupied.getId());
        slot("2030-05-03T10:00:00+09:00");

        assertThat(service.availableDates(DAY.minusDays(1), DAY.plusDays(2)))
                .containsExactly(DAY, DAY.plusDays(2));
        service.cancel(OWNER, reservations.findAll().getFirst().getId());
        assertThat(service.availableDates(DAY, DAY.plusDays(2)))
                .containsExactly(DAY, DAY.plusDays(1), DAY.plusDays(2));
    }

    @Test
    void startTimeIsExclusiveForBookingAndCancellation() {
        var slot = futureSlot();
        var reservation = service.reserve(OWNER, slot.getId());
        when(clock.instant()).thenReturn(slot.getStartsAt());
        expectError(() -> service.cancel(OWNER, reservation.reservationId()),
                ErrorCode.RESERVATION_CANCELLATION_CLOSED);
        expectError(() -> service.reserve(OTHER, slot.getId()), ErrorCode.SLOT_NOT_BOOKABLE);
        assertThat(service.availableSlots(DAY)).isEmpty();
    }

    @Test
    void pastUnoccupiedSlotCannotBeBooked() {
        var slot = slot("2030-04-30T22:00:00+09:00");
        expectError(() -> service.reserve(OWNER, slot.getId()), ErrorCode.SLOT_NOT_BOOKABLE);
        assertThat(reservations.count()).isZero();
    }

    @Test
    void validatesDateRangesIdsAndPageLimits() {
        expectError(() -> service.availableDates(DAY, DAY.minusDays(1)), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.availableDates(DAY, DAY.plusDays(31)), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.availableSlots(DAY.minusDays(2)), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.availableDates(LocalDate.MAX, LocalDate.MAX), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.availableSlots(LocalDate.of(10000, 1, 1)), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.reserve(OWNER, null), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.reserve(OWNER, 0L), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.cancel(OWNER, -1L), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.mine(OWNER, -1, 20), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.mine(OWNER, 0, 0), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.mine(OWNER, 0, 101), ErrorCode.INVALID_REQUEST);
        expectError(() -> service.mine(OWNER, Integer.MAX_VALUE, 100), ErrorCode.INVALID_REQUEST);
        assertThat(service.availableDates(DAY, DAY.plusDays(30))).isEmpty();
    }

    @Test
    void reportsMissingSlotReservationAndMember() {
        expectError(() -> service.reserve(OWNER, Long.MAX_VALUE), ErrorCode.SLOT_NOT_FOUND);
        expectError(() -> service.cancel(OWNER, Long.MAX_VALUE), ErrorCode.RESERVATION_NOT_FOUND);
        var slot = futureSlot();
        expectError(() -> service.reserve("missing-member", slot.getId()), ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void databaseRejectsDuplicateActiveReservationsEvenWithoutServiceLock() {
        var slot = futureSlot();
        service.reserve(OWNER, slot.getId());
        assertThatThrownBy(() -> reservations.saveAndFlush(new Reservation(owner, slot, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(activeCount(slot.getId())).isEqualTo(1);
    }

    @Test
    void databaseRejectsInvalidStateAndMismatchedActiveSlot() {
        var slot = futureSlot();
        var reservation = service.reserve(OWNER, slot.getId());
        var otherSlot = slot("2030-05-01T11:00:00+09:00");
        expectCheckViolation(() -> jdbc.update("update reservations set active_slot_id = null where id = ?",
                reservation.reservationId()));
        expectCheckViolation(() -> jdbc.update("update reservations set active_slot_id = ? where id = ?",
                otherSlot.getId(), reservation.reservationId()));
        expectCheckViolation(() -> jdbc.update("update reservations set status = 'UNKNOWN' where id = ?",
                reservation.reservationId()));
        expectCheckViolation(() -> jdbc.update("update reservations set status = 'CANCELLED' where id = ?",
                reservation.reservationId()));
        expectCheckViolation(() -> jdbc.update("""
                update reservations set status = 'CANCELLED', active_slot_id = null, cancelled_at = ? where id = ?
                """, LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC), reservation.reservationId()));
    }

    @Test
    void databaseRejectsInvalidSlotTimesAndDuplicateWindows() {
        var slot = futureSlot();
        assertThatThrownBy(() -> slots.saveAndFlush(new ReservationSlot(slot.getStartsAt(), slot.getEndsAt())))
                .isInstanceOf(DataIntegrityViolationException.class);
        expectCheckViolation(() -> jdbc.update("insert into reservation_slots(starts_at, ends_at) values (?, ?)",
                Timestamp.from(NOW), Timestamp.from(NOW)));
    }

    @Test
    void bookingRollbackDoesNotOccupySlot() {
        var slot = futureSlot();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.reserve(OWNER, slot.getId());
            status.setRollbackOnly();
        });
        assertThat(reservations.count()).isZero();
        assertThat(service.reserve(OTHER, slot.getId()).status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void cancellationRollbackKeepsOriginalReservationActive() {
        var slot = futureSlot();
        var reservation = service.reserve(OWNER, slot.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.cancel(OWNER, reservation.reservationId());
            status.setRollbackOnly();
        });
        expectError(() -> service.reserve(OTHER, slot.getId()), ErrorCode.SLOT_ALREADY_RESERVED);
        assertThat(reservations.findById(reservation.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @RepeatedTest(3)
    void simultaneousBookingsConfirmExactlyOneMember() throws Exception {
        var slot = futureSlot();
        List<Callable<ErrorCode>> attempts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String subject = "concurrent-reservation-" + i;
            members.saveAndFlush(new Member(AuthProvider.GOOGLE, subject, null, null));
            attempts.add(() -> attempt(() -> service.reserve(subject, slot.getId())));
        }
        var results = simultaneously(attempts);
        assertThat(results.stream().filter(result -> result == null).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result != null).toList())
                .hasSize(7).containsOnly(ErrorCode.SLOT_ALREADY_RESERVED);
        assertThat(activeCount(slot.getId())).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    void simultaneousCancellationsSucceedOnlyOnce() throws Exception {
        var slot = futureSlot();
        var reservation = service.reserve(OWNER, slot.getId());
        var results = simultaneously(List.of(
                () -> attempt(() -> service.cancel(OWNER, reservation.reservationId())),
                () -> attempt(() -> service.cancel(OWNER, reservation.reservationId()))));
        assertThat(results).containsExactlyInAnyOrder(null, ErrorCode.RESERVATION_ALREADY_CANCELLED);
        assertThat(activeCount(slot.getId())).isZero();
    }

    @RepeatedTest(3)
    void differentSlotsCanAllBeBookedConcurrently() throws Exception {
        List<Callable<ErrorCode>> attempts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            var slot = slots.saveAndFlush(new ReservationSlot(NOW.plusSeconds(7200 + i * 3600),
                    NOW.plusSeconds(9000 + i * 3600)));
            attempts.add(() -> attempt(() -> service.reserve(OWNER, slot.getId())));
        }
        assertThat(simultaneously(attempts)).hasSize(8).containsOnlyNulls();
        assertThat(reservations.count()).isEqualTo(8);
    }

    @RepeatedTest(3)
    void cancellationRacingBookingsNeverConfirmsMoreThanOneNewReservation() throws Exception {
        var slot = futureSlot();
        var original = service.reserve(OWNER, slot.getId());
        List<Callable<ErrorCode>> attempts = new ArrayList<>();
        attempts.add(() -> attempt(() -> service.cancel(OWNER, original.reservationId())));
        for (int i = 0; i < 7; i++) {
            attempts.add(() -> attempt(() -> service.reserve(OTHER, slot.getId())));
        }
        var results = simultaneously(attempts);
        assertThat(results.getFirst()).isNull();
        assertThat(results.subList(1, results.size()).stream().filter(result -> result == null).count())
                .isLessThanOrEqualTo(1);
        assertThat(results.stream().filter(result -> result != null).toList())
                .containsOnly(ErrorCode.SLOT_ALREADY_RESERVED);
        assertThat(reservations.findById(original.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        if (activeCount(slot.getId()) == 0) {
            service.reserve(OTHER, slot.getId());
        }
        assertThat(activeCount(slot.getId())).isEqualTo(1);
    }

    private long activeCount(Long slotId) {
        return jdbc.queryForObject("select count(*) from reservations where active_slot_id = ?", Long.class, slotId);
    }

    private void expectError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private void expectCheckViolation(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DataAccessException.class, exception -> {
            assertThat(exception.getMostSpecificCause()).isInstanceOf(SQLException.class);
            var sqlException = (SQLException) exception.getMostSpecificCause();
            // H2의 CHECK SQLState와 MySQL의 CHECK 오류 번호 확인
            assertThat("23513".equals(sqlException.getSQLState()) || sqlException.getErrorCode() == 3819)
                    .as("CHECK 제약조건 위반 여부").isTrue();
        });
    }

    private ErrorCode attempt(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException exception) {
            return exception.getErrorCode();
        }
    }

    private <T> List<T> simultaneously(List<Callable<T>> attempts) throws Exception {
        var ready = new CountDownLatch(attempts.size());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(attempts.size())) {
            var futures = attempts.stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 요청 시작 대기 시간 초과");
                }
                return action.call();
            })).toList();
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
