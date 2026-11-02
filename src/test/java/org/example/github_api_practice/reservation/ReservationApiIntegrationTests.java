package org.example.github_api_practice.reservation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ReservationApiIntegrationTests extends ReservationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper jsonMapper;

    @ParameterizedTest
    @ValueSource(strings = {"/api/reservations", "/api/reservation-slots?date=2030-05-01",
            "/api/reservation-slots/dates?from=2030-05-01&to=2030-05-03"})
    void anonymousReadsRequireLogin(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anonymousWritesWithCsrfStillRequireLogin() throws Exception {
        mvc.perform(post("/api/reservations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reservations/1/cancel").with(csrf()))
                .andExpect(status().isUnauthorized());
        assertThat(reservations.count()).isZero();
    }

    @Test
    void writesWithoutValidCsrfCannotCreateOrCancelReservation() throws Exception {
        var slot = futureSlot();
        mvc.perform(post("/api/reservations").with(oidcLogin().idToken(t -> t.subject(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":" + slot.getId() + "}"))
                .andExpect(status().isForbidden());
        assertThat(reservations.count()).isZero();
        var reservation = service.reserve(OWNER, slot.getId());
        mvc.perform(post("/api/reservations/{id}/cancel", reservation.reservationId())
                        .with(oidcLogin().idToken(t -> t.subject(OWNER))).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        assertThat(reservations.findById(reservation.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void realSessionAndCsrfTokenEnableBookingAndCancellation() throws Exception {
        var slot = futureSlot();
        var idToken = new OidcIdToken("synthetic", NOW, NOW.plusSeconds(300), Map.of("sub", OWNER));
        var principal = new DefaultOidcUser(List.of(), idToken);
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        var csrfResult = mvc.perform(get("/api/csrf").session(session)).andReturn().getResponse();
        var token = jsonMapper.readTree(csrfResult.getContentAsString()).get("data");

        var response = mvc.perform(post("/api/reservations").session(session)
                        .header(token.get("headerName").asString(), token.get("token").asString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":" + slot.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.startsAt").value("2030-05-01T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.cancelledAt").isEmpty())
                .andExpect(jsonPath("$.data.member").doesNotExist())
                .andReturn().getResponse();
        long reservationId = jsonMapper.readTree(response.getContentAsString())
                .get("data").get("reservationId").asLong();
        mvc.perform(post("/api/reservations/{id}/cancel", reservationId).session(session)
                        .header(token.get("headerName").asString(), token.get("token").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void availabilityApisReturnSeoulSlotsAndDates() throws Exception {
        var slot = futureSlot();
        mvc.perform(get("/api/reservation-slots").param("date", DAY.toString()).with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slotId").value(slot.getId()))
                .andExpect(jsonPath("$.data[0].startsAt").value("2030-05-01T10:00:00+09:00"));
        mvc.perform(get("/api/reservation-slots/dates").param("from", DAY.toString())
                        .param("to", DAY.plusDays(2).toString()).with(oidcLogin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").value("2030-05-01"));
    }

    @Test
    void requestCannotChooseAnotherMemberAsOwner() throws Exception {
        var slot = futureSlot();
        var other = members.findByProviderAndProviderId(
                org.example.github_api_practice.member.AuthProvider.GOOGLE, OTHER).orElseThrow();
        mvc.perform(post("/api/reservations").with(oidcLogin().idToken(t -> t.subject(OWNER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":" + slot.getId() + ",\"memberId\":" + other.getId() + "}"))
                .andExpect(status().isCreated());
        assertThat(reservations.findAll().getFirst().getMember().getId()).isEqualTo(owner.getId());
    }

    @Test
    void duplicateBookingReturns409AndClearCode() throws Exception {
        var slot = futureSlot();
        service.reserve(OWNER, slot.getId());
        mvc.perform(post("/api/reservations").with(oidcLogin().idToken(t -> t.subject(OTHER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":" + slot.getId() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("SLOT_ALREADY_RESERVED"));
    }

    @Test
    void mineIsPaginatedAndDoesNotExposeOtherMembersReservations() throws Exception {
        var own = service.reserve(OWNER, futureSlot().getId());
        service.reserve(OTHER, slot("2030-05-01T11:00:00+09:00").getId());
        mvc.perform(get("/api/reservations").with(oidcLogin().idToken(t -> t.subject(OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.content[0].reservationId").value(own.reservationId()));
    }

    @Test
    void cancellationChecksOwnerAndRejectsRepeatedCancellation() throws Exception {
        var reservation = service.reserve(OWNER, futureSlot().getId());
        mvc.perform(post("/api/reservations/{id}/cancel", reservation.reservationId())
                        .with(oidcLogin().idToken(t -> t.subject(OTHER))).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data.code").value("RESERVATION_FORBIDDEN"));
        service.cancel(OWNER, reservation.reservationId());
        mvc.perform(post("/api/reservations/{id}/cancel", reservation.reservationId())
                        .with(oidcLogin().idToken(t -> t.subject(OWNER))).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("RESERVATION_ALREADY_CANCELLED"));
    }

    @Test
    void getCannotCancelAReservation() throws Exception {
        var reservation = service.reserve(OWNER, futureSlot().getId());
        mvc.perform(get("/api/reservations/{id}/cancel", reservation.reservationId())
                        .with(oidcLogin().idToken(t -> t.subject(OWNER))))
                .andExpect(status().isMethodNotAllowed());
        assertThat(reservations.findById(reservation.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"slotId\":null}", "{\"slotId\":0}", "{\"slotId\":-1}",
            "{\"slotId\":\"wrong\"}", "{\"slotId\":9223372036854775808}", "{", "null"})
    void invalidBodiesReturn400WithoutCreatingReservation(String body) throws Exception {
        mvc.perform(post("/api/reservations").with(oidcLogin().idToken(t -> t.subject(OWNER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
        assertThat(reservations.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/reservation-slots", "/api/reservation-slots?date=wrong",
            "/api/reservation-slots?date=2030-02-30", "/api/reservation-slots/dates?from=2030-05-01",
            "/api/reservation-slots/dates?from=2030-05-02&to=2030-05-01",
            "/api/reservation-slots/dates?from=2030-05-01&to=2030-06-01",
            "/api/reservations?page=-1", "/api/reservations?size=101", "/api/reservations?page=wrong"})
    void invalidQueryParametersReturn400(String path) throws Exception {
        mvc.perform(get(path).with(oidcLogin().idToken(t -> t.subject(OWNER))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
    }

    @Test
    void nonexistentResourcesReturn404() throws Exception {
        mvc.perform(post("/api/reservations").with(oidcLogin().idToken(t -> t.subject(OWNER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":9223372036854775807}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data.code").value("SLOT_NOT_FOUND"));
        mvc.perform(post("/api/reservations/9223372036854775807/cancel")
                        .with(oidcLogin().idToken(t -> t.subject(OWNER))).with(csrf()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void unsupportedBodyContentTypeReturns415() throws Exception {
        mvc.perform(post("/api/reservations").with(oidcLogin().idToken(t -> t.subject(OWNER))).with(csrf())
                        .contentType(MediaType.TEXT_PLAIN).content("slotId=1"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.data.code").value("HTTP_415"));
    }

    @Test
    void unsupportedResponseTypeReturns406() throws Exception {
        mvc.perform(get("/api/reservations").with(oidcLogin().idToken(t -> t.subject(OWNER)))
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.data.code").value("HTTP_406"));
    }
}
