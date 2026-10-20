package com.keralarescue.backend.api;

import com.keralarescue.backend.data.EmergencyRepository;
import com.keralarescue.backend.live.RescueEvents;
import com.keralarescue.backend.data.EmergencyRepository.HelperView;
import com.keralarescue.backend.data.EmergencyRepository.ImageData;
import com.keralarescue.backend.data.EmergencyRepository.MessageView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.List;

/**
 * Helpers are registered by a dispatcher (responder key). Each gets an ID and a 6-digit PIN
 * and can then see and update only the requests assigned to them.
 */
@RestController
@RequestMapping("/api")
public class HelperController {
    private static final String RESPONDER = "X-Responder-Key";
    private static final String HELPER_ID = "X-Helper-Id";
    private static final String HELPER_PIN = "X-Helper-Pin";
    private static final List<String> HELPER_STATUSES = List.of("HELPER ON THE WAY", "HELPER ARRIVED", "RESCUE IN PROGRESS", "RESOLVED");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmergencyRepository repository;
    private final ResponderAuth responderAuth;
    private final HelperAuth helperAuth;
    private final RescueEvents events;

    public HelperController(EmergencyRepository repository, ResponderAuth responderAuth, HelperAuth helperAuth, RescueEvents events) {
        this.events = events;
        this.repository = repository;
        this.responderAuth = responderAuth;
        this.helperAuth = helperAuth;
    }

    public record HelperCreated(HelperView helper, String pin) { }

    // ---- Dispatcher side (responder key) ----

    @PostMapping("/helpers")
    public ResponseEntity<HelperCreated> create(@RequestBody HelperRequest request,
                                                @RequestHeader(value = RESPONDER, required = false) String key) {
        responderAuth.require(key);
        String name = request.name == null ? "" : request.name.trim();
        String phone = request.phone == null ? "" : request.phone.trim();
        if (name.isEmpty() || name.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Helper name must be 1 to 120 characters");
        if (phone.length() > 40) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone number is too long");
        if (repository.helperNameTaken(name)) throw new ResponseStatusException(HttpStatus.CONFLICT, "A helper with this name already exists");
        String pin = newPin();
        HelperView helper = repository.createHelper(name, phone, pin);
        return ResponseEntity.status(HttpStatus.CREATED).body(new HelperCreated(helper, pin));
    }

    @GetMapping("/helpers")
    public List<HelperView> list(@RequestHeader(value = RESPONDER, required = false) String key) {
        responderAuth.require(key);
        return repository.findHelpers();
    }

    @PostMapping("/helpers/{id}/reset-pin")
    public HelperCreated resetPin(@PathVariable String id, @RequestHeader(value = RESPONDER, required = false) String key) {
        responderAuth.require(key);
        HelperView helper = repository.findHelper(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Helper not found"));
        String pin = newPin();
        repository.setHelperPin(id, pin);
        return new HelperCreated(helper, pin);
    }

    @DeleteMapping("/helpers/{id}")
    public ResponseEntity<Void> remove(@PathVariable String id, @RequestHeader(value = RESPONDER, required = false) String key) {
        responderAuth.require(key);
        if (!repository.deleteHelper(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Helper not found");
        return ResponseEntity.noContent().build();
    }

    // ---- Helper side (helper ID + PIN) ----

    @GetMapping("/helper/me")
    public HelperView me(@RequestHeader(value = HELPER_ID, required = false) String id,
                         @RequestHeader(value = HELPER_PIN, required = false) String pin,
                         @RequestHeader(value = "Authorization", required = false) String bearer) {
        return helperAuth.require(id, pin, bearer);
    }

    @GetMapping("/helper/assignments")
    public List<EmergencyView> assignments(@RequestHeader(value = HELPER_ID, required = false) String id,
                                           @RequestHeader(value = HELPER_PIN, required = false) String pin,
                         @RequestHeader(value = "Authorization", required = false) String bearer) {
        HelperView helper = helperAuth.require(id, pin, bearer);
        return repository.findAssignedTo(helper.name());
    }

    @PatchMapping("/helper/assignments/{requestId}/status")
    public EmergencyView updateStatus(@PathVariable String requestId, @RequestBody StatusUpdateRequest body,
                                      @RequestHeader(value = HELPER_ID, required = false) String id,
                                      @RequestHeader(value = HELPER_PIN, required = false) String pin,
                         @RequestHeader(value = "Authorization", required = false) String bearer) {
        HelperView helper = helperAuth.require(id, pin, bearer);
        EmergencyView before = requireAssigned(requestId, helper);
        String wanted = body.status == null ? "" : body.status.trim().toUpperCase();
        StatusUpdateRequest update = new StatusUpdateRequest();
        if (wanted.equals("DECLINE")) {
            // The helper cannot take it: return it to the dispatcher as unassigned.
            update.status = "UNDER REVIEW";
            update.unassignHelper = true;
        } else if (HELPER_STATUSES.contains(wanted)) {
            update.status = wanted;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status not allowed for helpers");
        }
        EmergencyView after = repository.updateStatus(requestId, update).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        events.updated(before, after);
        return after;
    }

    @GetMapping("/helper/assignments/{requestId}/messages")
    public List<MessageView> messages(@PathVariable String requestId,
                                      @RequestHeader(value = HELPER_ID, required = false) String id,
                                      @RequestHeader(value = HELPER_PIN, required = false) String pin,
                         @RequestHeader(value = "Authorization", required = false) String bearer) {
        HelperView helper = helperAuth.require(id, pin, bearer);
        requireAssigned(requestId, helper);
        return repository.findMessages(requestId);
    }

    @PostMapping("/helper/assignments/{requestId}/messages")
    public ResponseEntity<MessageView> sendMessage(@PathVariable String requestId, @RequestBody MessageRequest request,
                                                   @RequestHeader(value = HELPER_ID, required = false) String id,
                                                   @RequestHeader(value = HELPER_PIN, required = false) String pin,
                         @RequestHeader(value = "Authorization", required = false) String bearer) {
        HelperView helper = helperAuth.require(id, pin, bearer);
        EmergencyView view = requireAssigned(requestId, helper);
        if (request.message == null || request.message.isBlank() || request.message.length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must contain 1 to 2000 characters");
        MessageView saved = repository.addMessage(requestId, "HELPER", helper.name(), request.message.trim());
        events.message(view, saved);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/helper/assignments/{requestId}/image")
    public ResponseEntity<byte[]> image(@PathVariable String requestId,
                                        @RequestHeader(value = HELPER_ID, required = false) String id,
                                        @RequestHeader(value = HELPER_PIN, required = false) String pin,
                         @RequestHeader(value = "Authorization", required = false) String bearer) {
        HelperView helper = helperAuth.require(id, pin, bearer);
        requireAssigned(requestId, helper);
        ImageData image = repository.findImage(requestId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"));
        MediaType type;
        try { type = MediaType.parseMediaType(image.contentType()); }
        catch (Exception ignored) { type = MediaType.IMAGE_JPEG; }
        if (!"image".equalsIgnoreCase(type.getType())) type = MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }

    private EmergencyView requireAssigned(String requestId, HelperView helper) {
        EmergencyView view = repository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        if (!helper.name().equalsIgnoreCase(view.assignedTeam()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This request is not assigned to you");
        return view;
    }

    private static String newPin() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
