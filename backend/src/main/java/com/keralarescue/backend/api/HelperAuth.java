package com.keralarescue.backend.api;

import com.keralarescue.backend.data.EmergencyRepository;
import com.keralarescue.backend.security.AuthTokens;
import com.keralarescue.backend.data.EmergencyRepository.HelperView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Checks a helper's ID + PIN (headers X-Helper-Id / X-Helper-Pin) and locks a helper out briefly after repeated wrong PINs. */
@Component
public class HelperAuth {
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 5 * 60 * 1000L;
    private final EmergencyRepository repository;
    private final AuthTokens tokens;
    private final Map<String, long[]> attempts = new ConcurrentHashMap<>();

    public HelperAuth(EmergencyRepository repository, AuthTokens tokens) {
        this.repository = repository;
        this.tokens = tokens;
    }

    /** Accepts either a login token ("Authorization: Bearer ...") or the helper ID + PIN headers. */
    public HelperView require(String helperId, String pin, String authorization) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String id = tokens.helperId(authorization.substring(7).trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired. Please sign in again."));
            return repository.findHelper(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This helper no longer exists"));
        }
        return require(helperId, pin);
    }

    public HelperView require(String helperId, String pin) {
        if (helperId == null || pin == null || helperId.isBlank() || pin.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Helper ID and PIN required");
        Optional<HelperView> known = repository.findHelper(helperId.trim());
        if (known.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong helper ID or PIN");
        long[] state = attempts.computeIfAbsent(known.get().helperId(), k -> new long[2]);
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state[1] > now) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong PINs. Try again in a few minutes.");
            if (repository.authenticateHelper(known.get().helperId(), pin.trim()).isPresent()) {
                state[0] = 0;
                return known.get();
            }
            if (++state[0] >= MAX_FAILURES) {
                state[0] = 0;
                state[1] = now + LOCK_MILLIS;
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong helper ID or PIN");
    }
}
