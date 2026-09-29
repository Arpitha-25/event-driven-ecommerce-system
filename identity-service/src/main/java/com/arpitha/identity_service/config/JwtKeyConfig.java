package com.arpitha.identity_service.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * The RSA key pair that signs access tokens. The private key never leaves this service;
 * the public key is published at /.well-known/jwks.json so the gateway can verify tokens.
 */
@Slf4j
@Configuration
public class JwtKeyConfig {

    private static final int GENERATED_KEY_SIZE = 2048;
    private static final ResourceLoader RESOURCES = new DefaultResourceLoader();

    @Bean
    public RSAKey signingKey(JwtProperties properties) throws JOSEException, IOException {
        if (hasText(properties.privateKeyLocation()) && hasText(properties.publicKeyLocation())) {
            RSAPrivateKey privateKey;
            RSAPublicKey publicKey;
            try (InputStream in = RESOURCES.getResource(properties.privateKeyLocation()).getInputStream()) {
                privateKey = RsaKeyConverters.pkcs8().convert(in);
            }
            try (InputStream in = RESOURCES.getResource(properties.publicKeyLocation()).getInputStream()) {
                publicKey = RsaKeyConverters.x509().convert(in);
            }
            RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint().build();
            log.info("Signing tokens with the configured RSA key (kid {})", key.getKeyID());
            return key;
        }

        RSAKey key = new RSAKeyGenerator(GENERATED_KEY_SIZE)
                .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                .keyIDFromThumbprint(true).generate();
        log.warn("No JWT key configured (app.jwt.private-key-location); generated key {} for this run. "
                + "Tokens issued now stop working when the service restarts.", key.getKeyID());
        return key;
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    /** Validates this service's own tokens, e.g. for GET /api/v1/auth/me. */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey signingKey, JwtProperties properties) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static boolean hasText(String location) {
        return location != null && !location.isBlank();
    }
}
