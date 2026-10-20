package com.keralarescue.backend.api;

import com.keralarescue.backend.data.EmergencyRepository;
import com.keralarescue.backend.live.EventHub;
import com.keralarescue.backend.security.AuthTokens;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live update streams. Browsers cannot set headers on an EventSource, so the login token (not the raw
 * responder key or PIN) is passed as a query parameter. The stream only says "something changed".
 */
@RestController
@RequestMapping("/api")
public class LiveController {
    private final EventHub hub;
    private final AuthTokens tokens;
    private final EmergencyRepository repository;

    public LiveController(EventHub hub, AuthTokens tokens, EmergencyRepository repository) {
        this.hub = hub;
        this.tokens = tokens;
        this.repository = repository;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter dispatcherStream(@RequestParam(value = "token", required = false) String token) {
        if (!tokens.isResponder(token)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again");
        return hub.subscribeResponder();
    }

    @GetMapping(value = "/helper/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter helperStream(@RequestParam(value = "token", required = false) String token) {
        String helperId = tokens.helperId(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again"));
        String name = repository.findHelper(helperId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again")).name();
        return hub.subscribeHelper(name);
    }
}
