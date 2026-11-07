package org.example.github_api_practice.calendar;

import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.Member;
import org.example.github_api_practice.member.MemberRepository;
import org.example.github_api_practice.reservation.Reservation;
import org.example.github_api_practice.reservation.ReservationRepository;
import org.example.github_api_practice.reservation.ReservationSlot;
import org.example.github_api_practice.reservation.ReservationSlotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {"calendar.sync.worker-enabled=true", "calendar.sync.poll-interval-ms=100"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CalendarWorkerSchedulingTests {
    @Autowired MemberRepository members;
    @Autowired ReservationSlotRepository slots;
    @Autowired ReservationRepository reservations;
    @Autowired CalendarSyncRepository syncs;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean CalendarSyncService service;

    @Test
    void scheduledWorkerDiscoversCommittedDatabasePlan() throws Exception {
        CountDownLatch discovered = new CountDownLatch(1);
        doAnswer(call -> { discovered.countDown(); return null; }).when(service).synchronize(any());
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Instant now = Instant.now();
                var member = members.saveAndFlush(new Member(AuthProvider.GOOGLE, "scheduled-fixture", null, null));
                var slot = slots.saveAndFlush(new ReservationSlot(now.plusSeconds(3600), now.plusSeconds(5400)));
                var reservation = reservations.saveAndFlush(new Reservation(member, slot, now));
                syncs.saveAndFlush(new ReservationCalendarSync(reservation.getId(), CalendarSyncStatus.PENDING_CREATE, now.minusSeconds(1)));
            });
            assertThat(discovered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            reservations.deleteAllInBatch();
            slots.deleteAllInBatch();
            members.deleteAllInBatch();
        }
    }
}
