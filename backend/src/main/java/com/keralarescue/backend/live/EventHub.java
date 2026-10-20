package com.keralarescue.backend.live;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes "something changed" signals to open dashboards over Server-Sent Events so they refresh immediately
 * instead of waiting for the next poll. Events carry only a type and a request id, never personal data;
 * the page then re-fetches through the normal authenticated API.
 */
@Component
public class EventHub {
    private static final long TIMEOUT_MS = 30 * 60_000L;
    private static final int MAX_CONNECTIONS = 400;

    /** scope is "R" for dispatchers or "H:&lt;helper name lower-case&gt;" for a helper. */
    private record Subscriber(SseEmitter emitter, String scope) { }

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public SseEmitter subscribeResponder() { return subscribe("R"); }

    public SseEmitter subscribeHelper(String helperName) { return subscribe("H:" + helperName.toLowerCase(Locale.ROOT)); }

    private SseEmitter subscribe(String scope) {
        if (subscribers.size() >= MAX_CONNECTIONS) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Too many live connections");
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Subscriber sub = new Subscriber(emitter, scope);
        subscribers.add(sub);
        emitter.onCompletion(() -> subscribers.remove(sub));
        emitter.onTimeout(() -> { subscribers.remove(sub); emitter.complete(); });
        emitter.onError(e -> subscribers.remove(sub));
        try { emitter.send(SseEmitter.event().name("hello").data("{}")); } catch (IOException | IllegalStateException e) { subscribers.remove(sub); }
        return emitter;
    }

    /** @param assignedTeam name of the helper the request is assigned to (may be null); that helper is notified as well as dispatchers. */
    public void publish(String type, String requestId, String assignedTeam) {
        String data = "{\"id\":\"" + (requestId == null ? "" : requestId.replace("\\", "").replace("\"", "")) + "\"}";
        String helperScope = assignedTeam == null || assignedTeam.isBlank() ? null : "H:" + assignedTeam.toLowerCase(Locale.ROOT);
        for (Subscriber s : subscribers) {
            if (s.scope().equals("R") || s.scope().equals(helperScope)) send(s, SseEmitter.event().name(type).data(data));
        }
    }

    @Scheduled(fixedDelay = 25_000L, initialDelay = 25_000L)
    public void heartbeat() {
        for (Subscriber s : subscribers) send(s, SseEmitter.event().comment("keep-alive"));
    }

    private void send(Subscriber s, SseEmitter.SseEventBuilder event) {
        try { s.emitter().send(event); }
        catch (IOException | IllegalStateException e) { subscribers.remove(s); }
    }
}
