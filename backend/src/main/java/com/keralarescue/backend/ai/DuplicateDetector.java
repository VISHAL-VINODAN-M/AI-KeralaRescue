package com.keralarescue.backend.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Finds the earliest still-open request that looks like the same incident: same phone number, or within
 * {@link #RADIUS_METERS} of it and reported within {@link #WINDOW}. It only flags; nothing is merged or hidden.
 */
public final class DuplicateDetector {
    private DuplicateDetector() { }

    public static final double RADIUS_METERS = 150;
    public static final Duration WINDOW = Duration.ofMinutes(30);

    public record Point(String id, Double latitude, Double longitude, String phone, Instant createdAt) { }

    public static Optional<String> findOriginal(Point target, List<Point> others) {
        String targetPhone = digits(target.phone());
        Point best = null;
        for (Point o : others) {
            if (o.id().equals(target.id())) continue;
            if (!o.createdAt().isBefore(target.createdAt()) && !o.createdAt().equals(target.createdAt())) continue; // only older ones
            if (Duration.between(o.createdAt(), target.createdAt()).compareTo(WINDOW) > 0) continue;
            boolean samePhone = targetPhone.length() >= 7 && targetPhone.equals(digits(o.phone()));
            boolean close = Geo.validCoordinates(target.latitude(), target.longitude()) && Geo.validCoordinates(o.latitude(), o.longitude())
                    && Geo.distanceMeters(target.latitude(), target.longitude(), o.latitude(), o.longitude()) <= RADIUS_METERS;
            if ((samePhone || close) && (best == null || o.createdAt().isBefore(best.createdAt()))) best = o;
        }
        return best == null ? Optional.empty() : Optional.of(best.id());
    }

    /** Last 10 digits, so +91 98470 12345 and 09847012345 compare equal. */
    static String digits(String phone) {
        if (phone == null) return "";
        String d = phone.replaceAll("\\D", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }
}
