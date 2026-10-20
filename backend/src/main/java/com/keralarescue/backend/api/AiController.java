package com.keralarescue.backend.api;

import com.keralarescue.backend.ai.AiEngine;
import com.keralarescue.backend.ai.ClaudeClient.AiException;
import com.keralarescue.backend.ai.ClaudeClient.Turn;
import com.keralarescue.backend.data.EmergencyRepository;
import com.keralarescue.backend.live.RescueEvents;
import com.keralarescue.backend.notify.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** AI endpoints: re-run triage, translate messages, situation brief, and the public safety chat. */
@RestController
@RequestMapping("/api")
public class AiController {
    private static final String RESPONDER = "X-Responder-Key";

    public static class TranslateRequest { public String text; public String target; }
    public static class ChatTurnDto { public String role; public String text; }
    public static class ChatRequest { public String message; public List<ChatTurnDto> history; }
    public record TranslateResponse(String translation) { }
    public record ChatResponse(String reply, String source) { }

    private final AiEngine ai;
    private final ResponderAuth auth;
    private final HelperAuth helperAuth;
    private final EmergencyRepository repository;
    private final RescueEvents events;
    private final NotificationService notifications;

    public AiController(AiEngine ai, ResponderAuth auth, HelperAuth helperAuth, EmergencyRepository repository,
                        RescueEvents events, NotificationService notifications) {
        this.ai = ai;
        this.auth = auth;
        this.helperAuth = helperAuth;
        this.repository = repository;
        this.events = events;
        this.notifications = notifications;
    }

    /** Which optional integrations are switched on, so the dashboard can show honest labels. */
    @GetMapping("/system/status")
    public Map<String, Object> status(@RequestHeader(value = RESPONDER, required = false) String key) {
        auth.require(key);
        return Map.of("ai", ai.isConfigured(), "sms", notifications.smsEnabled(), "push", notifications.pushEnabled());
    }

    @PostMapping("/emergencies/{id}/analyze")
    public ResponseEntity<Map<String, String>> analyze(@PathVariable String id,
                                                       @RequestHeader(value = RESPONDER, required = false) String key) {
        auth.require(key);
        if (repository.findById(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active request not found");
        events.analyzeById(id);
        return ResponseEntity.accepted().body(Map.of("status", "queued"));
    }

    @PostMapping("/translate")
    public TranslateResponse translate(@RequestBody TranslateRequest body,
                                       @RequestHeader(value = RESPONDER, required = false) String key,
                                       @RequestHeader(value = "X-Helper-Id", required = false) String helperId,
                                       @RequestHeader(value = "X-Helper-Pin", required = false) String pin,
                                       @RequestHeader(value = "Authorization", required = false) String bearer) {
        if (!auth.matches(key)) helperAuth.require(helperId, pin, bearer); // dispatchers and signed-in helpers only
        if (body.text == null || body.text.isBlank() || body.text.length() > 1500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text must contain 1 to 1500 characters");
        String target = body.target == null ? "en" : body.target.trim().toLowerCase();
        if (!target.equals("en") && !target.equals("ml")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target must be en or ml");
        if (!ai.isConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI translation is not set up on this server");
        try {
            return new TranslateResponse(ai.translate(body.text, target));
        } catch (AiException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Translation is unavailable right now");
        }
    }

    @GetMapping("/ai/situation")
    public Map<String, String> situation(@RequestHeader(value = RESPONDER, required = false) String key) {
        auth.require(key);
        Instant now = Instant.now();
        List<AiEngine.SituationItem> items = new ArrayList<>();
        for (EmergencyView v : repository.findRecent()) {
            items.add(new AiEngine.SituationItem(v.emergencyId(), v.emergencyType(), v.priority(), v.status(), v.peopleNeedingHelp(),
                    v.assignedTeam(), Duration.between(Instant.parse(v.createdAt()), now).toMinutes(), v.ai() == null ? null : v.ai().summary()));
        }
        AiEngine.Text brief = ai.situation(items);
        return Map.of("brief", brief.text(), "source", brief.source());
    }

    /** Public: anyone can ask for safety guidance. Capped by input size and by the rate limiter in SecurityFilter. */
    @PostMapping("/ai/safety-chat")
    public ChatResponse safetyChat(@RequestBody ChatRequest body) {
        if (body.message == null || body.message.isBlank() || body.message.length() > 600)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must contain 1 to 600 characters");
        List<Turn> history = new ArrayList<>();
        if (body.history != null) {
            for (ChatTurnDto t : body.history) {
                if (t == null || t.text == null || t.role == null) continue;
                String role = t.role.trim().toLowerCase();
                if (role.equals("user") || role.equals("assistant")) history.add(new Turn(role, t.text));
            }
        }
        AiEngine.Text reply = ai.safetyChat(body.message, history);
        return new ChatResponse(reply.text(), reply.source());
    }
}
