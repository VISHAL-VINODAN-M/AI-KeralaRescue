package com.keralarescue.backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * Issues and checks login tokens (JWT) for dispatchers and helpers so that the responder key and helper PINs are
 * sent once at sign-in instead of with every request. Set RESCUE_JWT_SECRET (16+ characters) so tokens survive a
 * restart and work across several server instances; otherwise a random secret is used for this run only.
 */
@Component
public class AuthTokens {
    private static final Logger log = LoggerFactory.getLogger(AuthTokens.class);
    public static final long TTL_SECONDS = 12 * 3600L;
    private final Jwt jwt;

    public AuthTokens(@Value("${rescue.jwt-secret:}") String configured) {
        byte[] secret;
        if (configured == null || configured.isBlank()) {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            log.warn("No RESCUE_JWT_SECRET set: login sessions will end whenever the server restarts.");
        } else {
            if (configured.length() < 16) throw new IllegalStateException("rescue.jwt-secret must be at least 16 characters");
            secret = configured.getBytes(StandardCharsets.UTF_8);
        }
        this.jwt = new Jwt(secret);
    }

    public String issueResponder() { return jwt.sign("responder", "responder", TTL_SECONDS); }

    public String issueHelper(String helperId) { return jwt.sign(helperId, "helper", TTL_SECONDS); }

    public boolean isResponder(String token) {
        return jwt.verify(token).map(c -> "responder".equals(c.role())).orElse(false);
    }

    public Optional<String> helperId(String token) {
        return jwt.verify(token).filter(c -> "helper".equals(c.role())).map(Jwt.Claims::subject);
    }
}
