package com.arpitha.identity_service.service;

import com.arpitha.identity_service.config.JwtProperties;
import com.arpitha.identity_service.dto.TokenResponse;
import com.arpitha.identity_service.entity.UserAccount;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final RSAKey signingKey;

    /**
     * Signed (RS256) access token. Claims: sub = user id, email, roles, iss, iat, exp.
     * The "kid" header lets the gateway pick the right key from the JWKS.
     */
    public TokenResponse issueAccessToken(UserAccount user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim("email", user.getEmail())
                .claim("roles", List.of(user.getRole().name()))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", properties.accessTokenTtl().toSeconds());
    }
}
