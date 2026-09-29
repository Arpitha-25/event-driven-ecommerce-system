package com.arpitha.identity_service.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Publishes the public signing key so the gateway can verify access tokens. */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final RSAKey signingKey;

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        // toPublicJWK() drops every private component of the key.
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }
}
