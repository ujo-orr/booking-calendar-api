package org.example.github_api_practice.reservation;

import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.calendar.CalendarSyncPlanner;
import org.example.github_api_practice.global.error.ErrorCode;
import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.Member;
import org.example.github_api_practice.member.MemberService;
import org.example.github_api_practice.reservation.dto.ReservationPageResponse;
import org.example.github_api_practice.reservation.dto.ReservationResponse;
import org.example.github_api_practice.reservation.dto.ReservationSlotResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ReservationService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate MAX_QUERY_DATE = LocalDate.of(9999, 12, 30);
    private final ReservationSlotRepository slotRepository;
    private final ReservationRepository reservationRepository;
    private final MemberService memberService;
    private final Clock clock;
    private final CalendarSyncPlanner calendarSyncPlanner;

    public ReservationService(ReservationSlotRepository slotRepository, ReservationRepository reservationRepository,
                              MemberService memberService, Clock clock, CalendarSyncPlanner calendarSyncPlanner) {
        this.slotRepository = slotRepository;
        this.reservationRepository = reservationRepository;
        this.memberService = memberService;
        this.clock = clock;
        this.calendarSyncPlanner = calendarSyncPlanner;
    }

    public List<ReservationSlotResponse> availableSlots(LocalDate date) {
        validateDates(date, date);
        return available(date, date).stream()
                .map(slot -> ReservationSlotResponse.from(slot, BUSINESS_ZONE)).toList();
    }

    public List<LocalDate> availableDates(LocalDate from, LocalDate to) {
        validateDates(from, to);
        return available(from, to).stream()
                .map(slot -> slot.getStartsAt().atZone(BUSINESS_ZONE).toLocalDate()).distinct().toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse reserve(String subject, Long slotId) {
        validateId(slotId);
        Member member = memberService.findByIdentity(AuthProvider.GOOGLE, subject);
        ReservationSlot slot = lockSlot(slotId);
        Instant now = clock.instant();
        if (!slot.getStartsAt().isAfter(now)) {
            throw new ApiException(ErrorCode.SLOT_NOT_BOOKABLE);
        }
        // 슬롯 잠금 이후 최신 커밋 상태를 확인하는 READ_COMMITTED 조회
        if (reservationRepository.existsByActiveSlotId(slotId)) {
            throw new ApiException(ErrorCode.SLOT_ALREADY_RESERVED);
        }
        Reservation reservation = reservationRepository.saveAndFlush(new Reservation(member, slot, now));
        calendarSyncPlanner.reserved(reservation.getId(), member.getId());
        return ReservationResponse.from(reservation, BUSINESS_ZONE);
    }

    public ReservationPageResponse mine(String subject, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        Member member = memberService.findByIdentity(AuthProvider.GOOGLE, subject);
        var result = reservationRepository.findMine(member.getId(), PageRequest.of(page, size));
        return new ReservationPageResponse(result.getContent().stream()
                .map(reservation -> ReservationResponse.from(reservation, BUSINESS_ZONE)).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse cancel(String subject, Long reservationId) {
        validateId(reservationId);
        Member member = memberService.findByIdentity(AuthProvider.GOOGLE, subject);
        // 엔티티를 미리 읽지 않고 슬롯 ID만 조회하는 잠금 순서 준비
        Long slotId = reservationRepository.findSlotIdById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        ReservationSlot slot = lockSlot(slotId);
        // 예약과 취소 모두 슬롯 → 예약 순서로 잠금 획득
        Reservation reservation = reservationRepository.findLockedById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.getMember().getId().equals(member.getId())) {
            throw new ApiException(ErrorCode.RESERVATION_FORBIDDEN);
        }
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.RESERVATION_ALREADY_CANCELLED);
        }
        Instant now = clock.instant();
        if (!slot.getStartsAt().isAfter(now)) {
            throw new ApiException(ErrorCode.RESERVATION_CANCELLATION_CLOSED);
        }
        reservation.cancel(now);
        reservationRepository.flush();
        calendarSyncPlanner.cancelled(reservationId);
        return ReservationResponse.from(reservation, BUSINESS_ZONE);
    }

    private ReservationSlot lockSlot(Long slotId) {
        return slotRepository.findLockedById(slotId)
                .orElseThrow(() -> new ApiException(ErrorCode.SLOT_NOT_FOUND));
    }

    private List<ReservationSlot> available(LocalDate from, LocalDate to) {
        return slotRepository.findAvailable(from.atStartOfDay(BUSINESS_ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant(), clock.instant());
    }

    private void validateDates(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        if (from == null || to == null || from.isBefore(today) || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) > 30 || to.isAfter(MAX_QUERY_DATE)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    private void validateId(Long id) {
        if (id == null || id < 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }
}
