package com.keralarescue.backend.api;

import com.keralarescue.backend.security.AuthTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Shared-secret check for responder/dashboard endpoints (header X-Responder-Key).
 * Set RESCUE_RESPONDER_KEY (or rescue.responder-key). If unset, a random key is
 * generated at startup and printed once in the log.
 */
@Component
public class ResponderAuth {
    private static final Logger log = LoggerFactory.getLogger(ResponderAuth.class);
    private final String key;
    private final AuthTokens tokens;

    public ResponderAuth(@Value("${rescue.responder-key:}") String configured, AuthTokens tokens) {
        this.tokens = tokens;
        if (configured == null || configured.isBlank()) {
            byte[] bytes = new byte[18];
            new SecureRandom().nextBytes(bytes);
            this.key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            log.warn("No RESCUE_RESPONDER_KEY set. Generated responder key for this run: {}", key);
        } else {
            if (configured.length() < 12) throw new IllegalStateException("rescue.responder-key must be at least 12 characters");
            this.key = configured;
        }
    }

    /** True for the shared responder key (used at sign-in) and only that. */
    public boolean matchesKey(String supplied) {
        if (supplied == null) return false;
        return MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    /** True for the responder key or for a valid dispatcher login token (X-Responder-Key carries either). */
    public boolean matches(String supplied) {
        if (supplied == null) return false;
        return matchesKey(supplied) || tokens.isResponder(supplied);
    }

    public void require(String supplied) {
        if (!matches(supplied)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Responder key required");
    }
}
