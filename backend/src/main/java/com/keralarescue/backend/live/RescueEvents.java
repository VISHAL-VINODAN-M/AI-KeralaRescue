package com.keralarescue.backend.live;

import com.keralarescue.backend.ai.AiEngine;
import com.keralarescue.backend.ai.DuplicateDetector;
import com.keralarescue.backend.ai.TriageInput;
import com.keralarescue.backend.ai.TriageResult;
import com.keralarescue.backend.api.EmergencyView;
import com.keralarescue.backend.data.EmergencyRepository;
import com.keralarescue.backend.data.EmergencyRepository.ImageData;
import com.keralarescue.backend.data.EmergencyRepository.MessageView;
import com.keralarescue.backend.notify.NotificationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * One place that reacts to changes: tells open dashboards (live updates), starts AI triage for new requests,
 * and sends SMS/push. Controllers call these after the database change has succeeded, and nothing here can
 * make the original request fail.
 */
@Service
public class RescueEvents {
    private final EventHub hub;
    private final Background background;
    private final AiEngine ai;
    private final EmergencyRepository repository;
    private final NotificationService notifications;

    public RescueEvents(EventHub hub, Background background, AiEngine ai, EmergencyRepository repository, NotificationService notifications) {
        this.hub = hub;
        this.background = background;
        this.ai = ai;
        this.repository = repository;
        this.notifications = notifications;
    }

    public void created(EmergencyView view, byte[] image) {
        safePublish("created", view);
        analyze(view, image);
    }

    public void updated(EmergencyView before, EmergencyView after) {
        safePublish("updated", after);
        if (before != null && after != null) {
            try { notifications.requestUpdated(before, after); } catch (RuntimeException ignored) { /* never fail the caller */ }
        }
    }

    public void message(EmergencyView view, MessageView message) {
        safePublish("message", view);
        try { notifications.messagePosted(view, message); } catch (RuntimeException ignored) { /* never fail the caller */ }
    }

    /** Runs triage in the background and stores the result; also used for the dispatcher's "re-analyse" button. */
    public void analyze(EmergencyView view, byte[] image) {
        background.run("triage " + view.emergencyId(), () -> {
            String contentType = view.imageContentType();
            TriageInput input = new TriageInput(view.emergencyType(), view.priority(), view.description(), view.conditionDetails(),
                    view.peopleNeedingHelp(), view.injuredCount(), image, contentType);
            TriageResult result = ai.triage(input);
            Optional<String> duplicate = DuplicateDetector.findOriginal(
                    new DuplicateDetector.Point(view.emergencyId(), view.latitude(), view.longitude(), view.phone(), java.time.Instant.parse(view.createdAt())),
                    toPoints(repository.findOpenPoints()));
            Optional<EmergencyView> saved = repository.saveAi(view.emergencyId(), result, duplicate.orElse(null));
            saved.ifPresent(v -> safePublish("ai", v));
        });
    }

    public void analyzeById(String id) {
        repository.findById(id).ifPresent(v -> analyze(v, repository.findImage(id).map(ImageData::bytes).orElse(null)));
    }

    private static List<DuplicateDetector.Point> toPoints(List<EmergencyRepository.OpenPoint> open) {
        return open.stream().map(o -> new DuplicateDetector.Point(o.id(), o.latitude(), o.longitude(), o.phone(), o.createdAt())).toList();
    }

    private void safePublish(String type, EmergencyView v) {
        try { hub.publish(type, v.emergencyId(), v.assignedTeam()); } catch (RuntimeException ignored) { /* live updates are best effort */ }
    }
}
