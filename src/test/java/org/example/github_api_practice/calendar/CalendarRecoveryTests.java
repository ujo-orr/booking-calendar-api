package org.example.github_api_practice.calendar;

import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.Member;
import org.example.github_api_practice.member.MemberRepository;
import org.example.github_api_practice.reservation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"calendar.http.connect-timeout=500ms", "calendar.http.read-timeout=1s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalendarRecoveryTests {
    static final MockCalendarServer google = new MockCalendarServer();
    static final Instant NOW = Instant.parse("2030-04-30T14:00:00Z");
    static final Instant START = Instant.parse("2030-05-01T01:00:00Z");
    @Autowired ReservationApplicationService application;
    @Autowired ReservationService reservations;
    @Autowired ReservationRepository reservationRepository;
    @Autowired ReservationSlotRepository slots;
    @Autowired MemberRepository members;
    @Autowired CalendarConnectionStore connections;
    @Autowired CalendarConnectionRepository connectionRepository;
    @Autowired CalendarSyncRepository syncs;
    @Autowired CalendarSyncService service;
    @Autowired CalendarTokenCipher cipher;
    @Autowired CalendarTokenProvider tokens;
    @Autowired CalendarHttpErrors errors;
    @Autowired CalendarSyncProperties properties;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @MockitoBean Clock clock;
    @MockitoSpyBean CalendarSyncStore store;
    final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    Member owner;

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry properties) {
        properties.add("calendar.api-base-url", () -> google.url("/calendar/v3"));
        properties.add("spring.security.oauth2.client.provider.google.token-uri", () -> google.url("/token"));
    }

    @BeforeEach
    void setUp() {
        cleanUp();
        google.reset();
        now.set(NOW);
        when(clock.instant()).thenAnswer(call -> now.get());
        when(clock.withZone(any(ZoneId.class))).thenAnswer(call -> Clock.fixed(now.get(), call.getArgument(0)));
        owner = members.saveAndFlush(new Member(AuthProvider.GOOGLE, "recovery-owner", null, null));
        members.saveAndFlush(new Member(AuthProvider.GOOGLE, "recovery-other", null, null));
        connect();
    }

    @AfterEach
    void cleanUp() {
        reservationRepository.deleteAllInBatch();
        slots.deleteAllInBatch();
        members.deleteAllInBatch();
    }

    @AfterAll
    static void stop() {
        google.close();
    }

    @Test
    void committedPlanIsPickedUpByNewWorkerWithoutApplicationCallback() {
        Long id = plan();
        assertThat(google.creates.get()).isZero();
        worker().runOnce();
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        worker().runOnce();
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void failedCreationIsRecoveredAfterPersistedDueTimeWithSameEventId() {
        google.createStatus = 503;
        Long id = book();
        String key = sync(id).getEventKey();
        assertThat(sync(id).getAttemptCount()).isEqualTo(1);
        service.synchronize(id);
        assertThat(google.creates.get()).isEqualTo(1);
        google.createStatus = 200;
        due(id);
        worker().runOnce();
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(sync(id).getGoogleEventId()).isEqualTo(key);
        assertThat(google.events).hasSize(1).containsKey(key);
    }

    @Test
    void responseLossAfterCreationIsRecoveredByLookupWithoutAnotherPost() {
        google.dropCreateResponse = true;
        Long id = book();
        assertThat(sync(id).getLastError()).isEqualTo(CalendarFailure.OUTCOME_UNKNOWN);
        int sent = google.creates.get();
        assertThat(google.events).hasSize(1);
        google.dropCreateResponse = false;
        due(id);
        worker().runOnce();
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(google.creates.get()).isEqualTo(sent);
    }

    @Test
    void readTimeoutAfterServerCreationDoesNotGenerateAnotherEvent() {
        google.createDelayMillis = 1500;
        Long id = book();
        assertThat(sync(id).getLastError()).isEqualTo(CalendarFailure.OUTCOME_UNKNOWN);
        assertThat(google.events).hasSize(1);
        google.createDelayMillis = 0;
        due(id);
        service.synchronize(id);
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void connectionFailureSchedulesRetryWithoutRollingBackReservation() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build());
        factory.setReadTimeout(Duration.ofMillis(200));
        var unreachable = RestClient.builder().requestFactory(factory).baseUrl("http://127.0.0.1:" + port).build();
        Long id = plan();
        new CalendarSyncService(store, new GoogleCalendarClient(unreachable, tokens, errors)).synchronize(id);
        assertThat(sync(id).getLastError()).isEqualTo(CalendarFailure.TRANSPORT_ERROR);
        assertThat(sync(id).getNextAttemptAt()).isAfter(now.get());
        assertThat(reservationRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void conflictIsResolvedByVerifyingTheExistingEvent() {
        google.createStatus = 409;
        google.conflictPublishesEvent = true;
        Long id = book();
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(google.creates.get()).isEqualTo(1);
        assertThat(google.events).hasSize(1);
    }

    @Test
    void invisibleConflictRetriesSameIdInsteadOfInventingANewOne() {
        google.createStatus = 409;
        Long id = book();
        String key = sync(id).getEventKey();
        assertThat(sync(id).getLastError()).isEqualTo(CalendarFailure.EVENT_CONFLICT);
        google.events.put(key, event(key, key, START));
        due(id);
        worker().runOnce();
        assertThat(sync(id).getGoogleEventId()).isEqualTo(key);
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void unrelatedEventIsNeitherAcceptedNorDeleted() {
        Long id = plan();
        String key = sync(id).getEventKey();
        google.events.put(key, event(key, "another-booking", START));
        service.synchronize(id);
        assertThat(sync(id).getLastError()).isEqualTo(CalendarFailure.EVENT_IDENTITY_MISMATCH);
        assertThat(sync(id).getNextAttemptAt()).isNull();
        application.cancel("recovery-owner", id);
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.DELETE_FAILED);
        assertThat(google.deletes.get()).isZero();
        assertThat(google.creates.get()).isZero();
        assertThat(google.events).containsKey(key);
    }

    @Test
    void failureAfterHttpSuccessIsRecoveredWithoutDuplicateEvent() {
        doThrow(new TransientDataAccessResourceException("storage-fixture")).doCallRealMethod()
                .when(store).created(any(), any());
        Long id = book();
        assertThat(sync(id).getLastError()).isEqualTo(CalendarFailure.OUTCOME_UNKNOWN);
        assertThat(google.events).hasSize(1);
        due(id);
        service.synchronize(id);
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void abandonedLeaseIsRecoveredAndOldResultsCannotOverwriteNewState() {
        Long id = plan();
        var abandoned = store.claim(id);
        assertThat(abandoned).isNotNull();
        assertThat(store.claim(id)).isNull();
        now.set(sync(id).getLeaseUntil().plusMillis(1));
        worker().runOnce();
        String actual = sync(id).getGoogleEventId();
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(store.created(abandoned, "stale-result")).isFalse();
        store.failed(abandoned, new CalendarException(CalendarFailure.PROVIDER_UNAVAILABLE));
        store.deleted(abandoned);
        assertThat(sync(id).getGoogleEventId()).isEqualTo(actual);
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void repeatedWorkerCrashesStopAtTheAttemptLimit() {
        Long id = plan();
        for (int i = 0; i < properties.maxAttempts(); i++) {
            assertThat(store.claim(id)).isNotNull();
            now.set(sync(id).getLeaseUntil().plusMillis(1));
        }
        assertThat(store.claim(id)).isNull();
        assertThat(store.status(id, owner.getId()).retryExhausted()).isTrue();
        assertThat(sync(id).getNextAttemptAt()).isNull();
        assertThat(google.creates.get()).isZero();
    }

    @Test
    void automaticRetriesUseBackoffAndStopAtTheLimit() {
        google.createStatus = 503;
        Long id = book();
        for (int attempt = 1; attempt < properties.maxAttempts(); attempt++) {
            Duration delay = Duration.between(now.get(), sync(id).getNextAttemptAt());
            long expected = 30L * (1L << (attempt - 1));
            assertThat(delay.toMillis()).isBetween(expected * 1000, expected * 1200);
            due(id);
            worker().runOnce();
        }
        assertThat(sync(id).getAttemptCount()).isEqualTo(properties.maxAttempts());
        assertThat(sync(id).getNextAttemptAt()).isNull();
        assertThat(store.status(id, owner.getId()).retryExhausted()).isTrue();
        now.set(now.get().plus(Duration.ofDays(1)));
        worker().runOnce();
        assertThat(google.creates.get()).isEqualTo(properties.maxAttempts());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403})
    void permanentProviderErrorsDoNotAutomaticallyRetry(int status) {
        google.createStatus = status;
        Long id = book();
        assertThat(sync(id).getNextAttemptAt()).isNull();
        now.set(now.get().plusSeconds(3600));
        worker().runOnce();
        assertThat(google.creates.get()).isEqualTo(1);
        assertThat(store.status(id, owner.getId()).retryExhausted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 429})
    void rateLimitReasonAndRetryAfterAreHonored(int status) {
        google.createStatus = status;
        google.errorReason = "rateLimitExceeded";
        google.retryAfter = "120";
        Long id = book();
        assertThat(sync(id).getLastError()).isEqualTo(CalendarFailure.RATE_LIMITED);
        assertThat(sync(id).getNextAttemptAt()).isAfterOrEqualTo(now.get().plusSeconds(120));
        now.set(now.get().plusSeconds(119));
        worker().runOnce();
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void retryAfterHttpDateIsSupported() {
        google.createStatus = 429;
        google.retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(NOW.plusSeconds(180), ZoneOffset.UTC));
        Long id = book();
        assertThat(sync(id).getNextAttemptAt()).isAfterOrEqualTo(NOW.plusSeconds(180));
    }

    @Test
    void unavailableDeleteIsRetriedWithTheOriginalId() {
        Long id = book();
        String key = sync(id).getGoogleEventId();
        google.deleteStatus = 503;
        application.cancel("recovery-owner", id);
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.DELETE_FAILED);
        google.deleteStatus = 204;
        due(id);
        worker().runOnce();
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
        assertThat(sync(id).getGoogleEventId()).isEqualTo(key);
        assertThat(google.events).isEmpty();
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void cancellationAfterManualRequeueStillChecksAnUnknownCreateResult() {
        google.dropCreateResponse = true;
        Long id = book();
        assertThat(google.events).hasSize(1);
        google.dropCreateResponse = false;
        store.requestRetry(id, owner.getId());
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.PENDING_CREATE);
        application.cancel("recovery-owner", id);
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
        assertThat(google.events).isEmpty();
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void periodicChecksDetectChangedTimesWithoutOverwritingGoogle() {
        Long id = book();
        String key = sync(id).getEventKey();
        google.events.put(key, event(key, key, START.plusSeconds(3600)));
        due(id);
        worker().runOnce();
        assertThat(sync(id).getLastError()).isEqualTo(CalendarFailure.EVENT_MISMATCH);
        assertThat(sync(id).getNextAttemptAt()).isNull();
        assertThat(google.creates.get()).isEqualTo(1);
        assertThat(google.deletes.get()).isZero();
    }

    @Test
    void missingPreviouslySyncedEventIsReportedWithoutCreatingAnotherId() {
        Long id = book();
        google.events.clear();
        due(id);
        worker().runOnce();
        assertThat(sync(id).getLastError()).isEqualTo(CalendarFailure.EVENT_MISSING);
        assertThat(sync(id).getNextAttemptAt()).isNull();
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void lateExternalCreationAfterDeletionIsCleanedUpByPeriodicCheck() {
        Long id = book();
        String key = sync(id).getEventKey();
        application.cancel("recovery-owner", id);
        google.events.put(key, event(key, key, START));
        due(id);
        worker().runOnce();
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
        assertThat(google.events).isEmpty();
    }

    @Test
    void cancelledCreateWithExpiredLeaseCannotResurrectLocalState() throws Exception {
        Long id = plan();
        google.createEntered = new CountDownLatch(1);
        google.releaseCreate = new CountDownLatch(1);
        google.createPersisted = new CountDownLatch(1);
        google.persistAfterRelease = true;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var original = executor.submit(() -> service.synchronize(id));
            assertThat(google.createEntered.await(3, TimeUnit.SECONDS)).isTrue();
            application.cancel("recovery-owner", id);
            now.set(now.get().plusSeconds(61));
            worker().runOnce();
            assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
            google.releaseCreate.countDown();
            assertThat(google.createPersisted.await(3, TimeUnit.SECONDS)).isTrue();
            original.get(5, TimeUnit.SECONDS);
            assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
            due(id);
            worker().runOnce();
            assertThat(google.events).isEmpty();
            assertThat(reservationRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        } finally {
            google.releaseCreate.countDown();
        }
    }

    @RepeatedTest(3)
    void competingWorkersOnlyCreateOneEvent() throws Exception {
        Long id = plan();
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            jobs.add(() -> { service.synchronize(id); return null; });
        }
        parallel(jobs);
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(google.events).hasSize(1);
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @RepeatedTest(3)
    void cancellationRacingRetryWorkersLeavesNoActiveEvent() throws Exception {
        google.createStatus = 503;
        Long id = book();
        google.createStatus = 200;
        due(id);
        List<Callable<Void>> jobs = new ArrayList<>();
        jobs.add(() -> { application.cancel("recovery-owner", id); return null; });
        for (int i = 0; i < 7; i++) {
            jobs.add(() -> { service.synchronize(id); return null; });
        }
        parallel(jobs);
        assertThat(reservationRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        if (sync(id).getNextAttemptAt() != null) {
            due(id);
            worker().runOnce();
        }
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
        assertThat(google.events).isEmpty();
    }

    @Test
    void legacyUnknownCreationIsBlockedRatherThanBlindlyReposted() throws Exception {
        Long id = plan();
        jdbc.update("""
                update reservation_calendar_sync set event_key=null, create_dispatched=true, status='CREATING'
                where reservation_id=?
                """, id);
        worker().runOnce();
        assertThat(sync(id).getLastError()).isEqualTo(CalendarFailure.LEGACY_OUTCOME_UNKNOWN);
        assertThat(sync(id).getNextAttemptAt()).isNull();
        assertThat(google.creates.get()).isZero();
        mvc.perform(post("/api/reservations/{id}/calendar/retry", id).with(oidcLogin().idToken(t -> t.subject("recovery-owner"))).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("CALENDAR_RECOVERY_REQUIRED"));
    }

    @Test
    void legacyKnownEventCanBeVerifiedAndDeletedWithoutChangingItsId() {
        Long id = plan();
        jdbc.update("""
                update reservation_calendar_sync set event_key=null, google_event_id='legacy123',
                create_dispatched=true, status='SYNCED' where reservation_id=?
                """, id);
        google.events.put("legacy123", event("legacy123", "old-version", START));
        worker().runOnce();
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        application.cancel("recovery-owner", id);
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
        assertThat(google.events).isEmpty();
        assertThat(google.creates.get()).isZero();
    }

    @Test
    void manualRecoveryChecksLoginCsrfOwnerAndBusyLease() throws Exception {
        Long id = plan();
        mvc.perform(post("/api/reservations/{id}/calendar/retry", id).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reservations/{id}/calendar/retry", id).with(oidcLogin().idToken(t -> t.subject("recovery-owner"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/reservations/{id}/calendar/retry", id).with(csrf()).with(oidcLogin().idToken(t -> t.subject("recovery-other"))))
                .andExpect(status().isForbidden());
        store.claim(id);
        mvc.perform(post("/api/reservations/{id}/calendar/retry", id).with(csrf()).with(oidcLogin().idToken(t -> t.subject("recovery-owner"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("CALENDAR_SYNC_BUSY"));
    }

    @Test
    void manualRecoveryResetsBudgetAfterPermissionIsFixed() throws Exception {
        google.createStatus = 403;
        Long id = book();
        String key = sync(id).getEventKey();
        google.createStatus = 200;
        mvc.perform(post("/api/reservations/{id}/calendar/retry", id).with(csrf()).with(oidcLogin().idToken(t -> t.subject("recovery-owner"))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.attemptCount").value(0));
        worker().runOnce();
        assertThat(sync(id).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(sync(id).getGoogleEventId()).isEqualTo(key);
        mvc.perform(get("/api/reservations/{id}/calendar", id).with(oidcLogin().idToken(t -> t.subject("recovery-owner"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.leaseToken").doesNotExist())
                .andExpect(jsonPath("$.data.nextAttemptAt").isNotEmpty());
    }

    @Test
    void concurrentBookingsShareOneTokenRefresh() throws Exception {
        now.set(NOW.plusSeconds(3700));
        google.refreshEntered = new CountDownLatch(1);
        google.releaseRefresh = new CountDownLatch(1);
        Long first = plan();
        Long second = reservations.reserve("recovery-owner", slot(1).getId()).reservationId();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var original = executor.submit(() -> service.synchronize(first));
            assertThat(google.refreshEntered.await(3, TimeUnit.SECONDS)).isTrue();
            service.synchronize(second);
            assertThat(sync(second).getLastError()).isEqualTo(CalendarFailure.LOCAL_CONFLICT);
            google.releaseRefresh.countDown();
            original.get(5, TimeUnit.SECONDS);
            due(second);
            worker().runOnce();
            assertThat(sync(first).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
            assertThat(sync(second).getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
            assertThat(google.refreshes.get()).isEqualTo(1);
        } finally {
            google.releaseRefresh.countDown();
        }
    }

    @Test
    void expiredRefreshLeaseRejectsStaleResults() {
        now.set(NOW.plusSeconds(3700));
        var abandoned = connections.claimRefresh(owner.getId());
        assertThatThrownBy(() -> connections.claimRefresh(owner.getId())).isInstanceOf(CalendarException.class);
        now.set(now.get().plusSeconds(61));
        var replacement = connections.claimRefresh(owner.getId());
        assertThatThrownBy(() -> connections.refreshed(abandoned.snapshot(), abandoned.leaseToken(),
                "stale-access", null, now.get().plusSeconds(3600), CalendarScopes.EVENTS_OWNED))
                .isInstanceOf(CalendarException.class);
        connections.finishRefresh(owner.getId(), replacement.leaseToken(), false);
        assertThat(tokens.accessToken(owner.getId())).isEqualTo("renewed-access-fixture");
        assertThat(google.refreshes.get()).isEqualTo(1);
    }

    private void connect() {
        connections.connect(owner.getId(), new OAuth2AuthorizedClient(registrations.findByRegistrationId(CalendarScopes.REGISTRATION_ID),
                "recovery-owner", new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "recovery-access", NOW, NOW.plusSeconds(3600), Set.of(CalendarScopes.EVENTS_OWNED)),
                new OAuth2RefreshToken("recovery-refresh", NOW)));
    }

    private Long plan() {
        return reservations.reserve("recovery-owner", slot(0).getId()).reservationId();
    }

    private Long book() {
        return application.reserve("recovery-owner", slot(0).getId()).reservationId();
    }

    private ReservationSlot slot(int index) {
        return slots.saveAndFlush(new ReservationSlot(START.plusSeconds(index * 3600L), START.plusSeconds(index * 3600L + 1800)));
    }

    private ReservationCalendarSync sync(Long id) {
        return syncs.findById(id).orElseThrow();
    }

    private void due(Long id) {
        assertThat(sync(id).getNextAttemptAt()).isNotNull();
        now.set(sync(id).getNextAttemptAt().plusMillis(1));
    }

    private CalendarSyncWorker worker() {
        return new CalendarSyncWorker(store, service, cipher);
    }

    private String event(String id, String marker, Instant start) {
        return json.writeValueAsString(Map.of("id", id, "status", "confirmed",
                "start", Map.of("dateTime", start.atOffset(ZoneOffset.UTC).toString()),
                "end", Map.of("dateTime", start.plusSeconds(1800).atOffset(ZoneOffset.UTC).toString()),
                "extendedProperties", Map.of("private", Map.of(GoogleCalendarClient.MARKER, marker))));
    }

    private <T> List<T> parallel(List<Callable<T>> jobs) throws Exception {
        var ready = new CountDownLatch(jobs.size());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(jobs.size())) {
            var futures = jobs.stream().map(job -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("worker start timeout");
                }
                return job.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
