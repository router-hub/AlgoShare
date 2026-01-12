package com.algoshare.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

@Configuration
@Slf4j
public class RsaKeyConfig {

    @Value("${algoshare.auth.rsa.public-key:}")
    private String publicKeyPem;

    @Value("${algoshare.auth.rsa.private-key:}")
    private String privateKeyPem;

    @Bean
    public KeyPair keyPair() {
        try {
            // Try to load from environment first (PRODUCTION)
            if (!publicKeyPem.isEmpty() && !privateKeyPem.isEmpty()) {
                log.info("[RSA_KEY_LOAD] Loading RSA keys from environment configuration");
                return loadKeyPairFromPem(publicKeyPem, privateKeyPem);
            }

            // Fallback to generating new keys (DEVELOPMENT ONLY)
            log.warn("[RSA_KEY_GENERATE] Generating new RSA key pair - NOT RECOMMENDED FOR PRODUCTION");
            log.warn("[RSA_KEY_GENERATE] Please provide keys via algoshare.auth.rsa.public-key and algoshare.auth.rsa.private-key");

            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            // Log the generated keys (DEVELOPMENT ONLY - REMOVE IN PRODUCTION)
            logGeneratedKeys(keyPair);

            return keyPair;

        } catch (Exception ex) {
            log.error("[RSA_KEY_ERROR] Failed to load/generate RSA key pair", ex);
            throw new IllegalStateException("Unable to initialize RSA Key Pair", ex);
        }
    }

    @Bean
    public RSAKey rsaKey(KeyPair keyPair) {
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    @Bean
    public JWKSet jwkSet(RSAKey rsaKey) {
        log.info("[JWK_SET_INIT] JWK Set initialized | keyId={}", rsaKey.getKeyID());
        return new JWKSet(rsaKey);
    }

    /**
     * Load key pair from PEM format strings
     */
    private KeyPair loadKeyPairFromPem(String publicKeyPem, String privateKeyPem) throws Exception {
        // Remove PEM headers and decode Base64
        String publicKeyContent = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        String privateKeyContent = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyContent);
        byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyContent);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        // Generate public key
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);

        // Generate private key
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);

        log.info("[RSA_KEY_LOAD_SUCCESS] RSA keys loaded successfully from environment");
        return new KeyPair(publicKey, privateKey);
    }

    /**
     * Log generated keys in PEM format (DEVELOPMENT ONLY)
     */
    private void logGeneratedKeys(KeyPair keyPair) {
        try {
            String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" +
                    Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded()) +
                    "\n-----END PUBLIC KEY-----";

            String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
                    Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded()) +
                    "\n-----END PRIVATE KEY-----";

            log.info("[RSA_KEY_GENERATED] Public Key:\n{}", publicKeyPem);
            log.info("[RSA_KEY_GENERATED] Private Key:\n{}", privateKeyPem);
            log.warn("[RSA_KEY_WARNING] SAVE THESE KEYS SECURELY! They will be different on next restart!");

        } catch (Exception e) {
            log.error("[RSA_KEY_LOG_ERROR] Failed to log generated keys", e);
        }
    }
}
