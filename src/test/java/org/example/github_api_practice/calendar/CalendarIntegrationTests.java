package org.example.github_api_practice.calendar;

import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.Member;
import org.example.github_api_practice.member.MemberRepository;
import org.example.github_api_practice.reservation.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalendarIntegrationTests {
    static final MockCalendarServer google = new MockCalendarServer();
    static final Instant NOW = Instant.parse("2030-04-30T14:00:00Z");
    @Autowired ReservationApplicationService application;
    @Autowired ReservationService reservations;
    @Autowired ReservationRepository reservationRepository;
    @Autowired ReservationSlotRepository slots;
    @Autowired MemberRepository members;
    @Autowired CalendarConnectionStore connections;
    @Autowired CalendarConnectionRepository connectionRepository;
    @Autowired CalendarSyncRepository syncs;
    @Autowired CalendarSyncService synchronization;
    @Autowired CalendarSyncStore syncStore;
    @Autowired CalendarTokenCipher cipher;
    @Autowired CalendarTokenProvider tokens;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @MockitoBean Clock clock;
    @MockitoSpyBean GoogleCalendarClient calendarClient;
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
        when(clock.instant()).thenReturn(NOW);
        when(clock.withZone(any(ZoneId.class))).thenAnswer(call -> Clock.fixed(clock.instant(), call.getArgument(0)));
        owner = members.saveAndFlush(new Member(AuthProvider.GOOGLE, "calendar-owner", null, null));
        members.saveAndFlush(new Member(AuthProvider.GOOGLE, "calendar-other", null, null));
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
    void createsEventWithSeoulTimesAndPersistsEventId() throws Exception {
        connect(true);
        var result = application.reserve("calendar-owner", slot().getId());
        var sync = syncs.findById(result.reservationId()).orElseThrow();
        assertThat(sync.getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(sync.getGoogleEventId()).isEqualTo(sync.getEventKey());
        assertThat(google.authorization).isEqualTo("Bearer access-fixture");
        var body = json.readTree(google.createBody);
        assertThat(body.get("start").get("dateTime").asString()).isEqualTo("2030-05-01T10:00:00+09:00");
        assertThat(body.get("end").get("dateTime").asString()).isEqualTo("2030-05-01T10:30:00+09:00");
        assertThat(body.get("start").get("timeZone").asString()).isEqualTo("Asia/Seoul");
        assertThat(google.createBody).doesNotContain("access-fixture", "refresh-fixture", "email");
    }

    @Test
    void deletesEventAfterLocalCancellationAndKeepsHistory() {
        connect(true);
        var result = application.reserve("calendar-owner", slot().getId());
        application.cancel("calendar-owner", result.reservationId());
        var sync = syncs.findById(result.reservationId()).orElseThrow();
        assertThat(sync.getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
        assertThat(sync.getGoogleEventId()).isEqualTo(sync.getEventKey());
        assertThat(google.deletedPath).endsWith("/events/" + sync.getEventKey());
        assertThat(reservationRepository.findById(result.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void disconnectedReservationsContinueWorkingWithoutHttpCalls() {
        var result = application.reserve("calendar-owner", slot().getId());
        assertThat(syncs.findById(result.reservationId()).orElseThrow().getStatus())
                .isEqualTo(CalendarSyncStatus.NOT_CONNECTED);
        application.cancel("calendar-owner", result.reservationId());
        assertThat(syncs.findById(result.reservationId()).orElseThrow().getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
        assertThat(google.creates.get() + google.deletes.get()).isZero();
    }

    @Test
    void tokenDatabaseContainsOnlyCiphertextAndReconnectKeepsExistingRefreshToken() {
        connect(true);
        var stored = connectionRepository.findById(owner.getId()).orElseThrow();
        assertThat(stored.getEncryptedAccessToken()).doesNotContain("access-fixture");
        assertThat(stored.getEncryptedRefreshToken()).doesNotContain("refresh-fixture");
        connect(false);
        stored = connectionRepository.findById(owner.getId()).orElseThrow();
        assertThat(cipher.decrypt(stored.getEncryptedRefreshToken(), owner.getId() + ":refresh"))
                .isEqualTo("refresh-fixture");
    }

    @Test
    void expiredAccessTokenIsRefreshedAndMissingRefreshRotationPreservesOldToken() {
        connect(true);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3700));
        var result = application.reserve("calendar-owner", slot().getId());
        assertThat(syncs.findById(result.reservationId()).orElseThrow().getStatus()).isEqualTo(CalendarSyncStatus.SYNCED);
        assertThat(google.refreshes.get()).isEqualTo(1);
        assertThat(google.tokenForm).contains("grant_type=refresh_token", "refresh_token=refresh-fixture", "client_id=");
        assertThat(google.authorization).isEqualTo("Bearer renewed-access-fixture");
        var saved = connectionRepository.findById(owner.getId()).orElseThrow();
        assertThat(cipher.decrypt(saved.getEncryptedAccessToken(), owner.getId() + ":access")).isEqualTo("renewed-access-fixture");
        assertThat(cipher.decrypt(saved.getEncryptedRefreshToken(), owner.getId() + ":refresh")).isEqualTo("refresh-fixture");
    }

    @Test
    void rotatedRefreshTokenIsStoredEncrypted() {
        connect(true);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3700));
        google.refreshResponse = "{\"access_token\":\"renewed\",\"refresh_token\":\"rotated\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
        tokens.accessToken(owner.getId());
        var saved = connectionRepository.findById(owner.getId()).orElseThrow();
        assertThat(cipher.decrypt(saved.getEncryptedRefreshToken(), owner.getId() + ":refresh")).isEqualTo("rotated");
    }

    @Test
    void missingRefreshTokenRequiresReconsentAfterExpiryWithoutRollingBackReservation() {
        connect(false);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3700));
        var result = application.reserve("calendar-owner", slot().getId());
        assertThat(reservationRepository.findById(result.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertFailure(result.reservationId(), CalendarSyncStatus.CREATE_FAILED, CalendarFailure.REAUTH_REQUIRED);
        assertThat(connections.status(owner.getId()).state()).isEqualTo("REAUTH_REQUIRED");
        assertThat(google.refreshes.get() + google.creates.get()).isZero();
    }

    @Test
    void revokedRefreshTokenRequiresReconsent() {
        connect(true);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3700));
        google.refreshStatus = 400;
        google.refreshResponse = "{\"error\":\"invalid_grant\",\"error_description\":\"sensitive-fixture\"}";
        var result = application.reserve("calendar-owner", slot().getId());
        assertFailure(result.reservationId(), CalendarSyncStatus.CREATE_FAILED, CalendarFailure.REAUTH_REQUIRED);
        assertThat(connections.status(owner.getId()).state()).isEqualTo("REAUTH_REQUIRED");
        assertThat(google.creates.get()).isZero();
    }

    @Test
    void invalidOAuthClientIsConfigurationFailureRatherThanRevokedConsent() {
        connect(true);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3700));
        google.refreshStatus = 401;
        google.refreshResponse = "{\"error\":\"invalid_client\"}";
        var result = application.reserve("calendar-owner", slot().getId());
        assertFailure(result.reservationId(), CalendarSyncStatus.CREATE_FAILED, CalendarFailure.CONFIGURATION_ERROR);
        assertThat(connectionRepository.findById(owner.getId()).orElseThrow().isReauthRequired()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"access_token\":\"new\",\"expires_in\":0,\"token_type\":\"Bearer\"}",
            "{\"access_token\":\"new\",\"expires_in\":3600,\"token_type\":\"Other\"}"})
    void invalidRefreshResponsesCannotOverwriteStoredTokens(String response) {
        connect(true);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3700));
        google.refreshResponse = response;
        var result = application.reserve("calendar-owner", slot().getId());
        assertFailure(result.reservationId(), CalendarSyncStatus.CREATE_FAILED, CalendarFailure.INVALID_RESPONSE);
        assertThat(cipher.decrypt(connectionRepository.findById(owner.getId()).orElseThrow().getEncryptedAccessToken(),
                owner.getId() + ":access")).isEqualTo("access-fixture");
        assertThat(google.creates.get()).isZero();
    }

    @Test
    void refreshedTokenWithoutRequiredScopeRequiresReconsent() {
        connect(true);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3700));
        google.refreshResponse = "{\"access_token\":\"new\",\"expires_in\":3600,\"token_type\":\"Bearer\",\"scope\":\"openid\"}";
        var result = application.reserve("calendar-owner", slot().getId());
        assertFailure(result.reservationId(), CalendarSyncStatus.CREATE_FAILED, CalendarFailure.REAUTH_REQUIRED);
        assertThat(google.creates.get()).isZero();
    }

    @Test
    void lateRejectionOfAnOldTokenDoesNotRevokeNewConnection() {
        connect(true);
        tokens.rejected(owner.getId(), "older-access-token-fixture");
        assertThat(connections.status(owner.getId()).state()).isEqualTo("CONNECTED");
        tokens.rejected(owner.getId(), "access-fixture");
        assertThat(connections.status(owner.getId()).state()).isEqualTo("REAUTH_REQUIRED");
    }

    @Test
    void insufficientGrantedScopesCannotBeConnected() {
        var client = authorizedClient(true, Set.of("openid"));
        assertThatThrownBy(() -> connections.connect(owner.getId(), client))
                .isInstanceOf(org.example.github_api_practice.global.error.ApiException.class);
        assertThat(connectionRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void providerFailureIsRecordedAndDoesNotUndoReservation(int status) {
        connect(true);
        google.createStatus = status;
        google.createResponse = "{\"error\":\"private-provider-response\"}";
        var result = application.reserve("calendar-owner", slot().getId());
        assertFailure(result.reservationId(), CalendarSyncStatus.CREATE_FAILED,
                status == 429 ? CalendarFailure.RATE_LIMITED : CalendarFailure.PROVIDER_UNAVAILABLE);
        assertThat(reservationRepository.findById(result.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void insufficientPermissionAndRevokedAccessAreDistinguished() {
        connect(true);
        google.createStatus = 403;
        var first = application.reserve("calendar-owner", slot().getId());
        assertFailure(first.reservationId(), CalendarSyncStatus.CREATE_FAILED, CalendarFailure.PERMISSION_DENIED);
        google.createStatus = 401;
        var secondSlot = slots.saveAndFlush(new ReservationSlot(NOW.plusSeconds(7200), NOW.plusSeconds(9000)));
        var second = application.reserve("calendar-owner", secondSlot.getId());
        assertFailure(second.reservationId(), CalendarSyncStatus.CREATE_FAILED, CalendarFailure.REAUTH_REQUIRED);
        assertThat(connections.status(owner.getId()).state()).isEqualTo("REAUTH_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"id\":\"\"}", "not-json"})
    void malformedEventResponseCannotMarkSyncSuccessful(String response) {
        connect(true);
        google.createResponse = response;
        var result = application.reserve("calendar-owner", slot().getId());
        assertThat(syncs.findById(result.reservationId()).orElseThrow().getStatus()).isEqualTo(CalendarSyncStatus.CREATE_FAILED);
        assertThat(syncs.findById(result.reservationId()).orElseThrow().getGoogleEventId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void alreadyAbsentEventCountsAsDeleted(int status) {
        connect(true);
        var result = application.reserve("calendar-owner", slot().getId());
        google.deleteStatus = status;
        application.cancel("calendar-owner", result.reservationId());
        assertThat(syncs.findById(result.reservationId()).orElseThrow().getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
    }

    @Test
    void deleteFailureDoesNotUndoCancellationOrLoseEventId() {
        connect(true);
        var result = application.reserve("calendar-owner", slot().getId());
        google.deleteStatus = 503;
        application.cancel("calendar-owner", result.reservationId());
        assertFailure(result.reservationId(), CalendarSyncStatus.DELETE_FAILED, CalendarFailure.PROVIDER_UNAVAILABLE);
        assertThat(reservationRepository.findById(result.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(syncs.findById(result.reservationId()).orElseThrow().getGoogleEventId())
                .isEqualTo(syncs.findById(result.reservationId()).orElseThrow().getEventKey());
    }

    @Test
    void httpCallRunsAfterCommitWithoutDatabaseTransaction() {
        connect(true);
        AtomicBoolean observed = new AtomicBoolean();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            observed.set(true);
            return call.callRealMethod();
        }).when(calendarClient).ensureCreated(any());
        application.reserve("calendar-owner", slot().getId());
        assertThat(observed.get()).isTrue();
    }

    @Test
    void rollbackRemovesSyncPlanTogetherWithReservation() {
        connect(true);
        var slot = slot();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            reservations.reserve("calendar-owner", slot.getId());
            status.setRollbackOnly();
        });
        assertThat(reservationRepository.count()).isZero();
        assertThat(syncs.count()).isZero();
        assertThat(google.creates.get()).isZero();
    }

    @Test
    void failedSyncWaitsUntilPersistedRetryTime() {
        connect(true);
        google.createStatus = 503;
        var result = application.reserve("calendar-owner", slot().getId());
        synchronization.synchronize(result.reservationId());
        assertThat(google.creates.get()).isEqualTo(1);
    }

    @Test
    void cancellationDuringCreateLeadsToDeletionOnceIdIsKnown() throws Exception {
        connect(true);
        google.createEntered = new CountDownLatch(1);
        google.releaseCreate = new CountDownLatch(1);
        var slot = slot();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var booking = executor.submit(() -> application.reserve("calendar-owner", slot.getId()));
            assertThat(google.createEntered.await(3, TimeUnit.SECONDS)).isTrue();
            Long id = reservationRepository.findAll().getFirst().getId();
            application.cancel("calendar-owner", id);
            google.releaseCreate.countDown();
            booking.get(8, TimeUnit.SECONDS);
            assertThat(syncs.findById(id).orElseThrow().getStatus()).isEqualTo(CalendarSyncStatus.DELETED);
            assertThat(google.creates.get()).isEqualTo(1);
            assertThat(google.deletes.get()).isEqualTo(1);
        } finally {
            google.releaseCreate.countDown();
        }
    }

    @Test
    void statusApisRequireLoginCheckOwnershipAndDoNotExposeTokens() throws Exception {
        connect(true);
        var result = application.reserve("calendar-owner", slot().getId());
        mvc.perform(get("/api/calendar/connection")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/calendar/connection").with(oidcLogin().idToken(t -> t.subject("calendar-owner"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("CONNECTED"))
                .andExpect(jsonPath("$.data.refreshTokenAvailable").value(true))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.encryptedAccessToken").doesNotExist());
        mvc.perform(get("/api/reservations/{id}/calendar", result.reservationId())
                        .with(oidcLogin().idToken(t -> t.subject("calendar-owner"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SYNCED"))
                .andExpect(jsonPath("$.data.googleEventId").value(syncs.findById(result.reservationId()).orElseThrow().getEventKey()));
        mvc.perform(get("/api/reservations/{id}/calendar", result.reservationId())
                        .with(oidcLogin().idToken(t -> t.subject("calendar-other"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reservationHttpResponseStays201WhenCalendarFails() throws Exception {
        connect(true);
        google.createStatus = 503;
        var slot = slot();
        mvc.perform(post("/api/reservations").with(oidcLogin().idToken(t -> t.subject("calendar-owner"))).with(csrf())
                        .contentType("application/json").content("{\"slotId\":" + slot.getId() + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(syncs.findAll().getFirst().getStatus()).isEqualTo(CalendarSyncStatus.CREATE_FAILED);
    }

    private void connect(boolean refresh) {
        connections.connect(owner.getId(), authorizedClient(refresh, Set.of("openid", CalendarScopes.EVENTS_OWNED)));
    }

    private OAuth2AuthorizedClient authorizedClient(boolean refresh, Set<String> scopes) {
        return new OAuth2AuthorizedClient(registrations.findByRegistrationId(CalendarScopes.REGISTRATION_ID),
                "calendar-owner", new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "access-fixture", NOW, NOW.plusSeconds(3600), scopes),
                refresh ? new OAuth2RefreshToken("refresh-fixture", NOW) : null);
    }

    private ReservationSlot slot() {
        return slots.saveAndFlush(new ReservationSlot(Instant.parse("2030-05-01T01:00:00Z"),
                Instant.parse("2030-05-01T01:30:00Z")));
    }

    private void assertFailure(Long id, CalendarSyncStatus status, CalendarFailure failure) {
        var sync = syncs.findById(id).orElseThrow();
        assertThat(sync.getStatus()).isEqualTo(status);
        assertThat(sync.getLastError()).isEqualTo(failure);
    }
}
