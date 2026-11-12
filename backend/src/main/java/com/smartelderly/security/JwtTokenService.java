package com.smartelderly.security;

import com.smartelderly.config.AppProperties;
import com.smartelderly.model.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/** Issues and verifies HS256-signed JWT access tokens. */
@Component
public class JwtTokenService {
    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    public static final String ISSUER = "smart-elderly-portal";

    private final SecretKey key;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;

    public JwtTokenService(AppProperties props) {
        String secret = props.security().jwtSecret();
        byte[] bytes;
        if (secret == null || secret.length() < 32) {
            // A random key keeps the app secure; sessions simply end when the server restarts.
            log.warn("JWT_SECRET is missing or shorter than 32 characters. Using a random key; users must sign in again after a restart.");
            bytes = new byte[48];
            new SecureRandom().nextBytes(bytes);
        } else {
            bytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.decoder = nimbusDecoder;
        this.ttl = Duration.ofHours(Math.max(1, props.security().jwtExpirationHours()));
    }

    public JwtDecoder decoder() { return decoder; }

    public Duration ttl() { return ttl; }

    public String issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(user.getId())
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .claim("role", user.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
