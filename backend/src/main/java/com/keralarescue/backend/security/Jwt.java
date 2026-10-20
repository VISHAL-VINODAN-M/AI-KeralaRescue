package com.keralarescue.backend.security;

import com.keralarescue.backend.util.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Minimal HS256 JSON Web Token signer/verifier (no external library). */
public final class Jwt {
    public record Claims(String subject, String role, long expiresAtEpochSeconds) { }

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final String HEADER = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final LongSupplier clockSeconds;

    public Jwt(byte[] secret) { this(secret, () -> System.currentTimeMillis() / 1000L); }

    public Jwt(byte[] secret, LongSupplier clockSeconds) {
        if (secret == null || secret.length < 16) throw new IllegalArgumentException("JWT secret must be at least 16 bytes");
        this.secret = secret.clone();
        this.clockSeconds = clockSeconds;
    }

    public String sign(String subject, String role, long ttlSeconds) {
        long now = clockSeconds.getAsLong();
        String payload = ENC.encodeToString(Json.write(Map.of("sub", subject, "role", role, "iat", now, "exp", now + ttlSeconds))
                .getBytes(StandardCharsets.UTF_8));
        String signed = HEADER + "." + payload;
        return signed + "." + ENC.encodeToString(hmac(signed));
    }

    /** Returns the claims only if the signature is valid, the algorithm is HS256 and the token has not expired. */
    public Optional<Claims> verify(String token) {
        if (token == null) return Optional.empty();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) return Optional.empty();
        try {
            Map<String, Object> header = Json.object(new String(DEC.decode(parts[0]), StandardCharsets.UTF_8));
            if (!"HS256".equals(header.get("alg"))) return Optional.empty();
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, DEC.decode(parts[2]))) return Optional.empty();
            Map<String, Object> body = Json.object(new String(DEC.decode(parts[1]), StandardCharsets.UTF_8));
            Object exp = body.get("exp");
            if (!(exp instanceof Number n) || n.longValue() <= clockSeconds.getAsLong()) return Optional.empty();
            String sub = Json.str(body, "sub"), role = Json.str(body, "role");
            if (sub == null || role == null) return Optional.empty();
            return Optional.of(new Claims(sub, role, n.longValue()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
