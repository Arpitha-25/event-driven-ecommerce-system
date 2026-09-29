package com.arpitha.identity_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param issuer             the "iss" claim; the gateway only accepts tokens with this issuer
 * @param accessTokenTtl     how long an access token is valid
 * @param privateKeyLocation PKCS#8 PEM private key, e.g. "file:/run/secrets/jwt.key"; blank = generate a key at startup
 * @param publicKeyLocation  X.509 PEM public key matching the private key
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String issuer,
        Duration accessTokenTtl,
        String privateKeyLocation,
        String publicKeyLocation) {
}
