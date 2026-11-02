package org.example.github_api_practice.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

final class MockGoogleServer implements AutoCloseable {

    private final HttpServer server;
    private final RSAKey signingKey;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    volatile String nonce;
    volatile String email;
    volatile String fullName;
    volatile String userInfoSubject;
    volatile boolean expired;
    volatile int tokenStatus;
    volatile String lastTokenForm;

    MockGoogleServer() {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("phase01-test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> write(exchange, 200,
                    new JWKSet(signingKey.toPublicJWK()).toString()));
            server.createContext("/token", exchange -> {
                lastTokenForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (tokenStatus != 200) {
                    write(exchange, tokenStatus, "{\"error\":\"invalid_grant\"}");
                    return;
                }
                write(exchange, 200, jsonMapper.writeValueAsString(Map.of(
                        "access_token", "synthetic-access-token", "token_type", "Bearer", "expires_in", 300,
                        "scope", "openid profile email", "id_token", idToken())));
            });
            server.createContext("/userinfo", exchange -> write(exchange, 200,
                    jsonMapper.writeValueAsString(Map.of("sub", userInfoSubject,
                            "email", email, "name", fullName))));
            reset();
            server.start();
        } catch (IOException | JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    void reset() {
        nonce = "unset";
        email = "google@example.test";
        fullName = "구글 테스트 회원";
        userInfoSubject = "google-flow-sub";
        expired = false;
        tokenStatus = 200;
        lastTokenForm = null;
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private String idToken() {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience("test-google-client")
                .subject("google-flow-sub")
                .issueTime(Date.from(now.minusSeconds(expired ? 900 : 5)))
                .expirationTime(Date.from(now.plusSeconds(expired ? -600 : 300)))
                .claim("nonce", nonce)
                .claim("email", email)
                .claim("name", fullName)
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
