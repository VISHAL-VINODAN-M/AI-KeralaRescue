package com.keralarescue.backend.notify;

import com.keralarescue.backend.api.EmergencyView;
import com.keralarescue.backend.data.EmergencyRepository;
import com.keralarescue.backend.data.EmergencyRepository.HelperView;
import com.keralarescue.backend.data.EmergencyRepository.MessageView;
import com.keralarescue.backend.live.Background;
import com.keralarescue.backend.security.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Turns request changes into SMS and push notifications for the citizen and the assigned helper. */
@Service
public class NotificationService {
    private final EmergencyRepository repository;
    private final SmsClient sms;
    private final PushClient push;
    private final Background background;
    private final String publicUrl;
    // A phone number can be typed by anyone, so cap how many SMS one number can receive per hour.
    private final RateLimiter perNumber = new RateLimiter(6, 3_600_000L);

    public NotificationService(EmergencyRepository repository, SmsClient sms, PushClient push, Background background,
                               @Value("${rescue.public-url:}") String publicUrl) {
        this.repository = repository;
        this.sms = sms;
        this.push = push;
        this.background = background;
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim().replaceAll("/+$", "");
    }

    public boolean smsEnabled() { return sms.isConfigured(); }
    public boolean pushEnabled() { return push.isConfigured(); }

    /** Called after a request's status, assignment or priority may have changed. */
    public void requestUpdated(EmergencyView before, EmergencyView after) {
        if (!sms.isConfigured() && !push.isConfigured()) return;
        boolean newlyAssigned = after.assignedTeam() != null && !after.assignedTeam().isBlank()
                && !after.assignedTeam().equalsIgnoreCase(before.assignedTeam());
        boolean statusChanged = !Objects.equals(before.status(), after.status());
        if (!newlyAssigned && !statusChanged) return;
        background.run("notify-update", () -> {
            if (newlyAssigned) notifyHelper(after);
            String status = newlyAssigned ? "HELPER ASSIGNED" : after.status();
            Templates.Text text = Templates.citizenStatus(after.emergencyId(), status, after.assignedTeam());
            if (text != null) notifyCitizen(after, text);
        });
    }

    /** Called after someone other than the citizen posts a message on a request. */
    public void messagePosted(EmergencyView view, MessageView message) {
        if ("USER".equalsIgnoreCase(message.senderRole()) || !push.isConfigured()) return;
        background.run("notify-message", () -> {
            String body = message.message().length() > 140 ? message.message().substring(0, 140) + "…" : message.message();
            sendPush("CITIZEN", view.emergencyId(), "Message from " + message.senderName(), body, view.emergencyId());
        });
    }

    private void notifyHelper(EmergencyView r) {
        HelperView helper = repository.findHelperByName(r.assignedTeam()).orElse(null);
        if (helper == null) return;
        String link = publicUrl.isEmpty() ? null : publicUrl + "/helper.html?id=" + helper.helperId();
        String text = Templates.helperAssignmentSms(r.emergencyId(), r.emergencyType(), r.priority(), r.peopleNeedingHelp(),
                r.description(), r.latitude(), r.longitude(), link);
        sendPush("HELPER", helper.helperId(), "New assignment: " + r.priority(), text, r.emergencyId());
        if (helper.phone() != null && !helper.phone().isBlank() && allowSms(helper.phone())) sms.send(helper.phone(), text);
    }

    private void notifyCitizen(EmergencyView r, Templates.Text text) {
        sendPush("CITIZEN", r.emergencyId(), text.title(), text.push(), r.emergencyId());
        if (text.sms() != null && r.phone() != null && !r.phone().isBlank() && allowSms(r.phone())) sms.send(r.phone(), text.sms());
    }

    private boolean allowSms(String phone) {
        String n = SmsClient.normalize(phone);
        return sms.isConfigured() && n != null && perNumber.tryAcquire(n);
    }

    private void sendPush(String role, String refId, String title, String body, String requestId) {
        if (!push.isConfigured()) return;
        List<String> tokens = repository.findDeviceTokens(role, refId);
        for (String token : tokens) {
            PushClient.Result result = push.send(token, title, body, Map.of("emergencyId", requestId, "type", "status"));
            if (result == PushClient.Result.INVALID_TOKEN) repository.deleteDeviceToken(token);
        }
    }
}
