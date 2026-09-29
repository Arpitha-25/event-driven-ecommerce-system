package com.arpitha.identity_service;

import com.arpitha.common.util.TimeZoneNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * identity-service against a real PostgreSQL (schema built by Flyway, validated by Hibernate).
 * Skipped automatically when Docker isn't available.
 */
@SpringBootTest(properties = {
        "app.admin.email=Admin@Test.Local",
        "app.admin.password=admin-password-123"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class IdentityServiceIntegrationTest {

    static {
        // Same as main(): PostgreSQL rejects legacy zone IDs such as "Asia/Calcutta".
        TimeZoneNormalizer.normalizeDefault();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final ObjectMapper json = new ObjectMapper();

    // ---------------------------------------------------------------------------------------

    @Test
    void register_CreatesUserAccount_WithHashedPassword() throws Exception {
        String email = uniqueEmail();

        JsonNode user = register(email, "correct-horse-battery", 201).get("data");

        assertEquals(email, user.get("email").asText());
        assertEquals("USER", user.get("role").asText());
        assertNull(user.get("password"), "the response never contains a password");
        String stored = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertTrue(stored.startsWith("$2"), "stored as a BCrypt hash, got " + stored);
        assertFalse(stored.contains("correct-horse-battery"));
    }

    @Test
    void register_SameEmailInAnyCase_IsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-battery", 201);

        JsonNode error = register(email.toUpperCase(), "another-password-1", 409);

        assertEquals("EMAIL_ALREADY_REGISTERED", error.get("errorCode").asText());
    }

    @Test
    void register_InvalidInput_IsRejectedWithFieldErrors() throws Exception {
        JsonNode errors = register("not-an-email", "short", 400);

        assertTrue(errors.has("email"), errors.toString());
        assertTrue(errors.has("password"), errors.toString());
    }

    @Test
    void login_ReturnsTokenThatVerifiesAgainstThePublishedKey() throws Exception {
        String email = uniqueEmail();
        String userId = register(email, "correct-horse-battery", 201).get("data").get("id").asText();

        JsonNode token = login(email, "correct-horse-battery", 200).get("data");
        assertEquals("Bearer", token.get("tokenType").asText());
        assertEquals(3600, token.get("expiresIn").asLong());

        // Verify exactly as the gateway will: signature against the JWKS, then the claims.
        SignedJWT jwt = SignedJWT.parse(token.get("accessToken").asText());
        RSAKey published = (RSAKey) publishedKeys().getKeyByKeyId(jwt.getHeader().getKeyID());
        assertNotNull(published, "the token's kid is in the JWKS");
        assertEquals(JWSAlgorithm.RS256, jwt.getHeader().getAlgorithm());
        assertTrue(jwt.verify(new RSASSAVerifier(published)), "signature verifies with the public key");

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertEquals(userId, claims.getSubject());
        assertEquals(email, claims.getStringClaim("email"));
        assertEquals(List.of("USER"), claims.getStringListClaim("roles"));
        assertEquals("ecommerce-identity-service", claims.getIssuer());
        assertEquals(3600, (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000);
    }

    @Test
    void login_WrongPasswordAndUnknownEmail_GiveTheSameAnswer() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-battery", 201);

        JsonNode wrongPassword = login(email, "wrong-password", 401);
        JsonNode unknownEmail = login(uniqueEmail(), "correct-horse-battery", 401);

        assertEquals("INVALID_CREDENTIALS", wrongPassword.get("errorCode").asText());
        assertEquals(wrongPassword.get("errorCode"), unknownEmail.get("errorCode"));
        assertEquals(wrongPassword.get("message"), unknownEmail.get("message"));
    }

    @Test
    void login_EmailIsCaseInsensitive() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-battery", 201);

        login("  " + email.toUpperCase() + "  ", "correct-horse-battery", 200);
    }

    @Test
    void me_RequiresAValidToken() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-battery", 201);
        String token = login(email, "correct-horse-battery", 200).get("data").get("accessToken").asText();

        MvcResult ok = mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertEquals(email, json.readTree(ok.getResponse().getContentAsString()).get("data").get("email").asText());

        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void me_RejectsTokensSignedByAnotherKey() throws Exception {
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("attacker").generate();
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(attackerKey.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer("ecommerce-identity-service")
                        .subject(UUID.randomUUID().toString())
                        .claim("roles", List.of("ADMIN"))
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                        .build());
        forged.sign(new RSASSASigner(attackerKey));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + forged.serialize()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_RejectsExpiredTokens_AndTokensFromAnotherIssuer() throws Exception {
        String email = uniqueEmail();
        String userId = register(email, "correct-horse-battery", 201).get("data").get("id").asText();

        String expired = sign(userId, "ecommerce-identity-service", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        String otherIssuer = sign(userId, "someone-else", Instant.now(), Instant.now().plusSeconds(600));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + otherIssuer)).andExpect(status().isUnauthorized());
    }

    @Test
    void jwks_PublishesOnlyThePublicKey() throws Exception {
        String body = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode key = json.readTree(body).get("keys").get(0);

        assertEquals("RSA", key.get("kty").asText());
        assertEquals("sig", key.get("use").asText());
        assertTrue(key.has("n") && key.has("e"), "public modulus and exponent present");
        for (String privatePart : List.of("d", "p", "q", "dp", "dq", "qi")) {
            assertFalse(key.has(privatePart), "private component '" + privatePart + "' must not be published");
        }
    }

    @Test
    void adminAccount_IsCreatedFromConfiguration_AndGetsTheAdminRole() throws Exception {
        String token = login("admin@test.local", "admin-password-123", 200).get("data").get("accessToken").asText();

        assertEquals(List.of("ADMIN"), SignedJWT.parse(token).getJWTClaimsSet().getStringListClaim("roles"));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = 'admin@test.local'", Integer.class));
    }

    // ---------------------------------------------------------------------------------------

    private JsonNode register(String email, String password, int expectedStatus) throws Exception {
        return postJson("/api/v1/auth/register", Map.of("email", email, "password", password, "fullName", "Test User"), expectedStatus);
    }

    private JsonNode login(String email, String password, int expectedStatus) throws Exception {
        return postJson("/api/v1/auth/login", Map.of("email", email, "password", password), expectedStatus);
    }

    private JsonNode postJson(String path, Map<String, String> body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JWKSet publishedKeys() throws Exception {
        return JWKSet.parse(mvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse().getContentAsString());
    }

    /** Signed with the service's real key, so only the claims can make it invalid. */
    private String sign(String subject, String issuer, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).subject(subject).issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("roles", List.of("USER")).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
