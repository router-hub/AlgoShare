package com.algoshare.auth.controller;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DiscoveryController {

    private final JWKSet jwkSet;

    @Value("${algoshare.auth.issuer-uri}")
    private String issuerUri;

    public DiscoveryController(JWKSet jwkSet) {
        this.jwkSet = jwkSet;
    }

    @GetMapping("/.well-known/openid-configuration")
    public Map<String, Object> getConfiguration() {
        return Map.of(
            "issuer", issuerUri,
            "jwks_uri", issuerUri + "/oauth2/jwks",
            "response_types_supported", new String[]{"code", "token"},
            "subject_types_supported", new String[]{"public"},
            "id_token_signing_alg_values_supported", new String[]{"RS256"}
        );
    }

    @GetMapping("/oauth2/jwks")
    public Map<String, Object> getJwkSet() {
        return jwkSet.toPublicJWKSet().toJSONObject();
    }
}
