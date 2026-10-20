package com.keralarescue.backend.api;

import com.keralarescue.backend.data.EmergencyRepository.HelperView;
import com.keralarescue.backend.security.AuthTokens;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Sign-in endpoints that exchange the responder key or a helper ID + PIN for a 12-hour login token (JWT). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public static class ResponderLogin { public String key; }
    public static class HelperLogin { public String helperId; public String pin; }
    public record TokenResponse(String token, long expiresInSeconds, HelperView helper) { }

    private final ResponderAuth responderAuth;
    private final HelperAuth helperAuth;
    private final AuthTokens tokens;

    public AuthController(ResponderAuth responderAuth, HelperAuth helperAuth, AuthTokens tokens) {
        this.responderAuth = responderAuth;
        this.helperAuth = helperAuth;
        this.tokens = tokens;
    }

    @PostMapping("/responder")
    public TokenResponse responder(@RequestBody ResponderLogin body) {
        if (!responderAuth.matchesKey(body.key)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong responder key");
        return new TokenResponse(tokens.issueResponder(), AuthTokens.TTL_SECONDS, null);
    }

    @PostMapping("/helper")
    public TokenResponse helper(@RequestBody HelperLogin body) {
        HelperView helper = helperAuth.require(body.helperId, body.pin);
        return new TokenResponse(tokens.issueHelper(helper.helperId()), AuthTokens.TTL_SECONDS, helper);
    }
}
