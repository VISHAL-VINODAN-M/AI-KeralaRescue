package com.keralarescue.backend.api;

import com.keralarescue.backend.ai.Geo;
import com.keralarescue.backend.ai.NearestHelpers;
import com.keralarescue.backend.data.EmergencyRepository;
import com.keralarescue.backend.data.EmergencyRepository.HelperLoad;
import com.keralarescue.backend.data.EmergencyRepository.HelperView;
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

import java.time.Instant;
import java.util.List;

/** Helper location sharing, nearest-helper suggestions, and push-notification device registration. */
@RestController
@RequestMapping("/api")
public class DispatchController {
    private static final String RESPONDER = "X-Responder-Key";

    public static class LocationRequest { public Double latitude; public Double longitude; }
    public static class DeviceRequest { public String token; public String emergencyId; }

    private final EmergencyRepository repository;
    private final ResponderAuth auth;
    private final HelperAuth helperAuth;

    public DispatchController(EmergencyRepository repository, ResponderAuth auth, HelperAuth helperAuth) {
        this.repository = repository;
        this.auth = auth;
        this.helperAuth = helperAuth;
    }

    @GetMapping("/emergencies/{id}/nearest-helpers")
    public List<NearestHelpers.Suggestion> nearest(@PathVariable String id,
                                                   @RequestHeader(value = RESPONDER, required = false) String key) {
        auth.require(key);
        EmergencyView view = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Active request not found"));
        List<NearestHelpers.Candidate> candidates = repository.findHelperLoads().stream().map(DispatchController::toCandidate).toList();
        return NearestHelpers.rank(view.latitude(), view.longitude(), candidates, Instant.now());
    }

    private static NearestHelpers.Candidate toCandidate(HelperLoad h) {
        return new NearestHelpers.Candidate(h.helperId(), h.name(), h.latitude(), h.longitude(), h.locationUpdatedAt(), h.active());
    }

    @PostMapping("/helper/location")
    public ResponseEntity<Void> helperLocation(@RequestBody LocationRequest body,
                                               @RequestHeader(value = "X-Helper-Id", required = false) String id,
                                               @RequestHeader(value = "X-Helper-Pin", required = false) String pin,
                                               @RequestHeader(value = "Authorization", required = false) String bearer) {
        HelperView helper = helperAuth.require(id, pin, bearer);
        if (!Geo.validCoordinates(body.latitude, body.longitude)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid location");
        repository.setHelperLocation(helper.helperId(), body.latitude, body.longitude);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/helper/device")
    public ResponseEntity<Void> helperDevice(@RequestBody DeviceRequest body,
                                             @RequestHeader(value = "X-Helper-Id", required = false) String id,
                                             @RequestHeader(value = "X-Helper-Pin", required = false) String pin,
                                             @RequestHeader(value = "Authorization", required = false) String bearer) {
        HelperView helper = helperAuth.require(id, pin, bearer);
        requireToken(body.token);
        repository.saveDeviceToken(body.token.trim(), "HELPER", helper.helperId());
        return ResponseEntity.noContent().build();
    }

    /** The citizen app registers its push token for its own request, proving ownership with the tracking token. */
    @PostMapping("/devices")
    public ResponseEntity<Void> citizenDevice(@RequestBody DeviceRequest body,
                                              @RequestHeader(value = "X-Tracking-Token", required = false) String trackingToken) {
        requireToken(body.token);
        if (body.emergencyId == null || !repository.tokenBelongsToRequest(body.emergencyId.trim(), trackingToken))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tracking token required");
        repository.saveDeviceToken(body.token.trim(), "CITIZEN", body.emergencyId.trim());
        return ResponseEntity.noContent().build();
    }

    private static void requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 400) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid device token");
    }
}
