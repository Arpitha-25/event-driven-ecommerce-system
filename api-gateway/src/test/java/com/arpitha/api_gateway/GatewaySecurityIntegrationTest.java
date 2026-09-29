package com.arpitha.api_gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gateway with a fake identity-service (serving a JWKS) and fake downstream services,
 * both played by one MockWebServer that records every request the gateway lets through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityIntegrationTest {

    private static final String ISSUER = "ecommerce-identity-service";
    private static final MockWebServer BACKEND = new MockWebServer();
    private static final List<RecordedRequest> FORWARDED = new CopyOnWriteArrayList<>();
    private static final RSAKey IDENTITY_KEY;
    private static final RSAKey ATTACKER_KEY;

    static {
        try {
            IDENTITY_KEY = new RSAKeyGenerator(2048).keyID("identity-key").keyUse(KeyUse.SIGNATURE).generate();
            ATTACKER_KEY = new RSAKeyGenerator(2048).keyID("attacker-key").keyUse(KeyUse.SIGNATURE).generate();
            String jwks = new JWKSet(IDENTITY_KEY.toPublicJWK()).toString();
            BACKEND.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if ("/.well-known/jwks.json".equals(request.getPath())) {
                        return new MockResponse().setHeader("Content-Type", "application/json").setBody(jwks);
                    }
                    FORWARDED.add(request);
                    MockResponse response = new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"from\":\"backend\"}");
                    // Like the real services, echo the correlation ID back.
                    String correlationId = request.getHeader("X-Correlation-ID");
                    return correlationId == null ? response : response.setHeader("X-Correlation-ID", correlationId);
                }
            });
            BACKEND.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void routeEverythingToTheMockBackend(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + BACKEND.getPort();
        registry.add("IDENTITY_SERVICE_URL", () -> url);
        registry.add("ORDER_SERVICE_URL", () -> url);
        registry.add("PRODUCT_SERVICE_URL", () -> url);
        registry.add("INVENTORY_SERVICE_URL", () -> url);
    }

    @AfterAll
    static void stopBackend() throws IOException {
        BACKEND.shutdown();
    }

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void clearRecordedRequests() {
        FORWARDED.clear();
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void protectedRoute_WithoutToken_Is401Json_AndNotForwarded() {
        client.get().uri("/api/v1/orders/1").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.errorCode").isEqualTo("UNAUTHORIZED");
        assertTrue(FORWARDED.isEmpty());
    }

    @Test
    void validUserToken_IsForwarded_WithVerifiedUserHeaders() throws Exception {
        String userId = UUID.randomUUID().toString();

        client.get().uri("/api/v1/orders/42")
                .header("Authorization", "Bearer " + token(userId, List.of("USER"), ISSUER, IDENTITY_KEY, 600))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.from").isEqualTo("backend");

        RecordedRequest forwarded = onlyForwardedRequest();
        assertEquals("/api/v1/orders/42", forwarded.getPath());
        assertEquals(userId, forwarded.getHeader("X-User-Id"));
        assertEquals(userId + "@example.com", forwarded.getHeader("X-User-Email"));
        assertEquals("USER", forwarded.getHeader("X-User-Roles"));
    }

    @Test
    void clientSuppliedUserHeaders_AreReplacedByTheTokensValues() throws Exception {
        String userId = UUID.randomUUID().toString();

        client.get().uri("/api/v1/orders/1")
                .header("Authorization", "Bearer " + token(userId, List.of("USER"), ISSUER, IDENTITY_KEY, 600))
                .header("X-User-Id", "someone-else")
                .header("X-User-Roles", "ADMIN")
                .header("x-user-anything", "spoofed")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest forwarded = onlyForwardedRequest();
        assertEquals(userId, forwarded.getHeader("X-User-Id"));
        assertEquals("USER", forwarded.getHeader("X-User-Roles"));
        assertNull(forwarded.getHeader("X-User-Anything"));
    }

    @Test
    void anonymousRequest_CannotSmuggleUserHeaders() {
        client.get().uri("/api/v1/products")
                .header("X-User-Id", "someone-else")
                .header("X-User-Roles", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest forwarded = onlyForwardedRequest();
        assertNull(forwarded.getHeader("X-User-Id"));
        assertNull(forwarded.getHeader("X-User-Roles"));
    }

    @Test
    void expiredToken_Is401() throws Exception {
        assertRejected(token(UUID.randomUUID().toString(), List.of("USER"), ISSUER, IDENTITY_KEY, -60));
    }

    @Test
    void tokenFromAnotherIssuer_Is401() throws Exception {
        assertRejected(token(UUID.randomUUID().toString(), List.of("USER"), "evil-issuer", IDENTITY_KEY, 600));
    }

    @Test
    void tokenSignedByAnotherKey_Is401() throws Exception {
        assertRejected(token(UUID.randomUUID().toString(), List.of("ADMIN"), ISSUER, ATTACKER_KEY, 600));
    }

    @Test
    void tokenSignedByAnotherKeyButClaimingTheRealKeyId_Is401() throws Exception {
        RSAKey impostor = new RSAKeyGenerator(2048).keyID(IDENTITY_KEY.getKeyID()).generate();
        assertRejected(token(UUID.randomUUID().toString(), List.of("ADMIN"), ISSUER, impostor, 600));
    }

    @Test
    void tokenWithEditedClaims_Is401() throws Exception {
        String genuine = token(UUID.randomUUID().toString(), List.of("USER"), ISSUER, IDENTITY_KEY, 600);
        String[] parts = genuine.split("\\.");
        JWTClaimsSet elevated = new JWTClaimsSet.Builder(SignedJWT.parse(genuine).getJWTClaimsSet())
                .claim("roles", List.of("ADMIN")).build();
        String tampered = parts[0] + "." + Base64URL.encode(elevated.toString()) + "." + parts[2];

        client.post().uri("/api/v1/products").header("Authorization", "Bearer " + tampered)
                .exchange().expectStatus().isUnauthorized();
        assertTrue(FORWARDED.isEmpty());
    }

    @Test
    void unsignedToken_Is401() {
        String header = Base64URL.encode("{\"alg\":\"none\"}").toString();
        String claims = Base64URL.encode("{\"iss\":\"" + ISSUER + "\",\"sub\":\"x\",\"roles\":[\"ADMIN\"],\"exp\":"
                + (Instant.now().getEpochSecond() + 600) + "}").toString();
        assertRejected(header + "." + claims + ".");
    }

    @Test
    void malformedToken_Is401() {
        assertRejected("this-is-not-a-jwt");
    }

    // ---------------------------------------------------------------- authorization

    @Test
    void products_AreReadableByAnyone_ButOnlyAdminsCanChangeThem() throws Exception {
        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/products/" + UUID.randomUUID()).exchange().expectStatus().isOk();
        assertEquals(2, FORWARDED.size());
        FORWARDED.clear();

        String user = token(UUID.randomUUID().toString(), List.of("USER"), ISSUER, IDENTITY_KEY, 600);
        client.post().uri("/api/v1/products").header("Authorization", "Bearer " + user)
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo("FORBIDDEN");
        client.post().uri("/api/v1/products").exchange().expectStatus().isUnauthorized();
        assertTrue(FORWARDED.isEmpty(), "a USER or anonymous write never reaches product-service");

        String admin = token(UUID.randomUUID().toString(), List.of("ADMIN"), ISSUER, IDENTITY_KEY, 600);
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            client.method(method).uri("/api/v1/products/" + UUID.randomUUID())
                    .header("Authorization", "Bearer " + admin).exchange().expectStatus().isOk();
        }
        assertEquals(3, FORWARDED.size());
    }

    @Test
    void inventory_NeedsLoginToRead_AndAdminToChange() throws Exception {
        String user = token(UUID.randomUUID().toString(), List.of("USER"), ISSUER, IDENTITY_KEY, 600);
        String admin = token(UUID.randomUUID().toString(), List.of("ADMIN"), ISSUER, IDENTITY_KEY, 600);

        client.get().uri("/api/v1/inventory").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/inventory").header("Authorization", "Bearer " + user).exchange().expectStatus().isOk();
        client.put().uri("/api/v1/inventory/" + UUID.randomUUID()).header("Authorization", "Bearer " + user)
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/v1/inventory").header("Authorization", "Bearer " + admin).exchange().expectStatus().isOk();

        assertEquals(2, FORWARDED.size());
    }

    @Test
    void registerAndLogin_ArePublic_ButOtherAuthRoutesNeedAToken() throws Exception {
        client.post().uri("/api/v1/auth/register").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/auth/me").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/auth/me")
                .header("Authorization", "Bearer " + token(UUID.randomUUID().toString(), List.of("USER"), ISSUER, IDENTITY_KEY, 600))
                .exchange().expectStatus().isOk();

        assertEquals(3, FORWARDED.size());
    }

    @Test
    void unknownRoute_IsDenied() throws Exception {
        client.get().uri("/api/v1/admin/secrets").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/admin/secrets")
                .header("Authorization", "Bearer " + token(UUID.randomUUID().toString(), List.of("ADMIN"), ISSUER, IDENTITY_KEY, 600))
                .exchange().expectStatus().isForbidden();
        assertTrue(FORWARDED.isEmpty());
    }

    @Test
    void healthEndpoint_IsPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    // ---------------------------------------------------------------- correlation ID

    @Test
    void correlationId_IsGeneratedWhenMissing_ReturnedAndForwarded() {
        String returned = client.get().uri("/api/v1/products").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-ID");

        assertNotNull(returned);
        assertDoesNotThrow(() -> UUID.fromString(returned));
        assertEquals(returned, onlyForwardedRequest().getHeader("X-Correlation-ID"));
    }

    @Test
    void correlationId_FromTheClient_IsKept_IncludingOnErrors() {
        client.get().uri("/api/v1/products").header("X-Correlation-ID", "trace-123").exchange()
                .expectHeader().valueEquals("X-Correlation-ID", "trace-123");
        assertEquals("trace-123", onlyForwardedRequest().getHeader("X-Correlation-ID"));

        client.get().uri("/api/v1/orders").header("X-Correlation-ID", "trace-401").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Correlation-ID", "trace-401")
                .expectBody().jsonPath("$.correlationId").isEqualTo("trace-401");
    }

    // ---------------------------------------------------------------- helpers

    private void assertRejected(String token) {
        client.get().uri("/api/v1/orders/1").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isUnauthorized();
        assertTrue(FORWARDED.isEmpty(), "a rejected token must never reach the service");
    }

    private RecordedRequest onlyForwardedRequest() {
        assertEquals(1, FORWARDED.size(), "exactly one request should have reached the backend");
        return FORWARDED.get(0);
    }

    private static String token(String subject, List<String> roles, String issuer, RSAKey key, long expiresInSeconds)
            throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject(subject)
                        .claim("email", subject + "@example.com")
                        .claim("roles", roles)
                        .issueTime(Date.from(now.minusSeconds(Math.max(0, -expiresInSeconds) + 60)))
                        .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
