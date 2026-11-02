package org.example.github_api_practice.reservation;

import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.Member;
import org.example.github_api_practice.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
abstract class ReservationTestSupport {

    static final Instant NOW = Instant.parse("2030-04-30T14:00:00Z");
    static final LocalDate DAY = LocalDate.of(2030, 5, 1);
    static final String OWNER = "reservation-owner";
    static final String OTHER = "reservation-other";

    @Autowired ReservationService service;
    @Autowired ReservationSlotRepository slots;
    @Autowired ReservationRepository reservations;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean Clock clock;
    Member owner;

    @BeforeEach
    void prepareReservations() {
        cleanReservations();
        when(clock.instant()).thenReturn(NOW);
        when(clock.withZone(any(ZoneId.class))).thenAnswer(invocation ->
                Clock.fixed(clock.instant(), invocation.getArgument(0)));
        owner = members.saveAndFlush(new Member(AuthProvider.GOOGLE, OWNER, "owner@example.test", "예약자"));
        members.saveAndFlush(new Member(AuthProvider.GOOGLE, OTHER, "other@example.test", "다른 회원"));
    }

    @AfterEach
    void cleanReservations() {
        reservations.deleteAllInBatch();
        slots.deleteAllInBatch();
        members.deleteAllInBatch();
    }

    ReservationSlot slot(String start) {
        Instant startsAt = OffsetDateTime.parse(start).toInstant();
        return slots.saveAndFlush(new ReservationSlot(startsAt, startsAt.plusSeconds(1800)));
    }

    ReservationSlot futureSlot() {
        return slot("2030-05-01T10:00:00+09:00");
    }
}
