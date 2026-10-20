package com.keralarescue.backend.api;

import com.keralarescue.backend.data.EmergencyRepository;
import com.keralarescue.backend.live.RescueEvents;
import com.keralarescue.backend.data.EmergencyRepository.ImageData;
import com.keralarescue.backend.data.EmergencyRepository.RescueSummary;
import com.keralarescue.backend.data.EmergencyRepository.RescueAnalytics;
import com.keralarescue.backend.data.EmergencyRepository.MessageView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Access model:
 *  - Citizens: create a request (no login). Everything else about a request needs its
 *    private tracking token (X-Tracking-Token) and is limited to that request.
 *  - Responders/helpers: need the shared X-Responder-Key for lists, analytics, photos,
 *    status/priority/assignment changes and responder messages.
 */
@RestController
@RequestMapping("/api")
public class EmergencyController {
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final String RESPONDER = "X-Responder-Key";
    private static final String TOKEN = "X-Tracking-Token";
    private final EmergencyRepository repository;
    private final ResponderAuth auth;
    private final RescueEvents events;

    public EmergencyController(EmergencyRepository repository, ResponderAuth auth, RescueEvents events) {
        this.repository = repository;
        this.auth = auth;
        this.events = events;
    }

    @PostMapping("/emergencies")
    public ResponseEntity<EmergencyView> create(@RequestBody EmergencyRequest request) {
        String id = request.emergencyId == null || request.emergencyId.isBlank()
                ? "MOB-" + UUID.randomUUID() : request.emergencyId.trim();
        if (id.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request ID too long");
        // The tracking token is the citizen's credential, so it must be unguessable.
        if (request.trackingToken == null || request.trackingToken.trim().length() < 16 || request.trackingToken.length() > 120)
            request.trackingToken = UUID.randomUUID().toString().replace("-", "");
        else request.trackingToken = request.trackingToken.trim();
        byte[] image = decodeImage(request.imageBase64);

        var existing = repository.findById(id).or(() -> repository.findHistoryById(id));
        if (existing.isPresent()) {
            // Idempotent retry only for the original sender; never reveal someone else's record.
            if (!tokenEquals(existing.get().trackingToken(), request.trackingToken))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request ID already in use");
            return ResponseEntity.ok(existing.get());
        }
        EmergencyView saved = repository.saveIfNew(request, id, image);
        events.created(saved, image);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/emergencies")
    public List<EmergencyView> recent(@RequestHeader(value = RESPONDER, required = false) String key) {
        auth.require(key);
        return repository.findRecent();
    }

    @GetMapping("/helpers/{helper}/assignments")
    public List<EmergencyView> helperAssignments(@PathVariable String helper,
                                                 @RequestHeader(value = RESPONDER, required = false) String key) {
        auth.require(key);
        if (helper.isBlank() || helper.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid helper name");
        return repository.findAssignedTo(helper.trim());
    }

    @GetMapping("/history")
    public List<EmergencyView> history(@RequestParam(required = false) String trackingToken,
                                       @RequestHeader(value = RESPONDER, required = false) String key) {
        if (trackingToken != null && !trackingToken.isBlank()) return repository.findHistoryByToken(trackingToken);
        auth.require(key);
        return repository.findHistory();
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Void> deleteHistory(@PathVariable String id,
                                              @RequestHeader(value = RESPONDER, required = false) String key) {
        auth.require(key);
        if (!repository.deleteHistory(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "History record not found");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/emergencies/{id}/messages")
    public List<MessageView> messages(@PathVariable String id,
                                      @RequestHeader(value = RESPONDER, required = false) String key,
                                      @RequestHeader(value = TOKEN, required = false) String token) {
        requireOwnerOrResponder(id, key, token);
        return repository.findMessages(id);
    }

    @PostMapping("/emergencies/{id}/messages")
    public ResponseEntity<MessageView> sendMessage(@PathVariable String id, @RequestBody MessageRequest request,
                                                   @RequestHeader(value = RESPONDER, required = false) String key,
                                                   @RequestHeader(value = TOKEN, required = false) String token) {
        boolean responder = auth.matches(key);
        EmergencyView view = requireOwnerOrResponder(id, key, token);
        if (request.message == null || request.message.isBlank() || request.message.length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must contain 1 to 2000 characters");
        String role = responder ? (request.senderRole == null ? "RESPONDER" : request.senderRole.trim().toUpperCase()) : "USER";
        if (!List.of("USER", "RESPONDER", "HELPER").contains(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sender role must be USER, RESPONDER, or HELPER");
        String name = request.senderName == null || request.senderName.isBlank() ? role : request.senderName.trim();
        if (name.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sender name is too long");
        MessageView saved = repository.addMessage(id, role, name, request.message.trim());
        events.message(view, saved);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/emergencies/{id}")
    public EmergencyView byId(@PathVariable String id,
                              @RequestHeader(value = RESPONDER, required = false) String key,
                              @RequestHeader(value = TOKEN, required = false) String token) {
        return requireOwnerOrResponder(id, key, token);
    }

    @PatchMapping("/emergencies/{id}/status")
    public EmergencyView updateStatus(@PathVariable String id, @RequestBody StatusUpdateRequest update,
                                      @RequestHeader(value = RESPONDER, required = false) String key,
                                      @RequestHeader(value = TOKEN, required = false) String token) {
        boolean responder = auth.matches(key);
        EmergencyView before = requireOwnerOrResponder(id, key, token);
        if (!responder) {
            // A citizen may only mark their own request as cancelled.
            boolean onlyCancel = update.status != null && update.status.trim().equalsIgnoreCase("CANCELLED")
                    && update.priority == null && update.assignedTeam == null && update.estimatedArrival == null && !update.unassignHelper;
            if (!onlyCancel) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a responder can change this");
        }
        List<String> statuses = List.of("SOS SENT", "RECEIVED", "UNDER REVIEW", "HELPER ASSIGNED", "HELPER ON THE WAY",
                "HELPER ARRIVED", "RESCUE IN PROGRESS", "RESOLVED", "CANCELLED", "REQUESTED", "ACKNOWLEDGED",
                "TEAM_ASSIGNED", "IN_PROGRESS", "COMPLETED", "CLOSED");
        if (update.status != null && !statuses.contains(update.status.trim().toUpperCase()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown emergency status");
        if (update.priority != null && !List.of("CRITICAL", "HIGH", "MEDIUM", "LOW").contains(update.priority.trim().toUpperCase()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Priority must be CRITICAL, HIGH, MEDIUM, or LOW");
        if (update.assignedTeam != null && update.assignedTeam.length() > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Helper name is too long");
        if (update.estimatedArrival != null && update.estimatedArrival.length() > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ETA is too long");
        EmergencyView after = repository.updateStatus(id, update).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        events.updated(before, after);
        return after;
    }

    @GetMapping("/emergencies/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable String id,
                                        @RequestHeader(value = RESPONDER, required = false) String key) {
        auth.require(key);
        ImageData image = repository.findImage(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"));
        MediaType type;
        try { type = MediaType.parseMediaType(image.contentType()); }
        catch (Exception ignored) { type = MediaType.IMAGE_JPEG; }
        if (!"image".equalsIgnoreCase(type.getType())) type = MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }

    @GetMapping("/summary")
    public RescueSummary summary(@RequestHeader(value = RESPONDER, required = false) String key) {
        auth.require(key);
        return repository.summary();
    }

    @GetMapping("/analytics")
    public RescueAnalytics analytics(@RequestHeader(value = RESPONDER, required = false) String key) {
        auth.require(key);
        return repository.analytics();
    }

    @GetMapping("/health")
    public String health() {
        return "Kerala Rescue backend is running";
    }

    /** Returns the active request if the caller is a responder or holds its tracking token; otherwise 404/401. */
    private EmergencyView requireOwnerOrResponder(String id, String key, String token) {
        boolean responder = auth.matches(key);
        EmergencyView view = repository.findById(id).orElse(null);
        if (view == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active request not found");
        if (!responder && !tokenEquals(view.trackingToken(), token))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tracking token or responder key required");
        return view;
    }

    private static boolean tokenEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] decodeImage(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        if (encoded.length() > ((MAX_IMAGE_BYTES + 2L) / 3L) * 4L + 8L)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Photo must be 2 MB or smaller");
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > MAX_IMAGE_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Photo must be 2 MB or smaller");
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Photo data is not valid Base64");
        }
    }
}
