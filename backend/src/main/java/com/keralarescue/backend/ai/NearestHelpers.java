package com.keralarescue.backend.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Ranks helpers for a request by straight-line distance, preferring helpers who are not already busy. */
public final class NearestHelpers {
    private NearestHelpers() { }

    /** A location older than this is treated as stale. */
    public static final Duration FRESH = Duration.ofMinutes(15);
    private static final double BUSY_PENALTY_METERS = 5000;
    private static final double ASSUMED_KMH = 20; // slow: flooded roads, rescue vehicles

    public record Candidate(String helperId, String name, Double latitude, Double longitude, Instant locationUpdatedAt, int activeAssignments) { }

    public record Suggestion(String helperId, String name, Double distanceMeters, Integer etaMinutes, int activeAssignments,
                             boolean locationFresh, Long locationAgeMinutes) { }

    public static List<Suggestion> rank(Double lat, Double lon, List<Candidate> candidates, Instant now) {
        boolean haveTarget = Geo.validCoordinates(lat, lon);
        List<Suggestion> out = new ArrayList<>();
        for (Candidate c : candidates) {
            boolean located = Geo.validCoordinates(c.latitude(), c.longitude());
            Long age = c.locationUpdatedAt() == null ? null : Math.max(0, Duration.between(c.locationUpdatedAt(), now).toMinutes());
            boolean fresh = located && c.locationUpdatedAt() != null && Duration.between(c.locationUpdatedAt(), now).compareTo(FRESH) <= 0;
            Double dist = haveTarget && located ? Geo.distanceMeters(lat, lon, c.latitude(), c.longitude()) : null;
            Integer eta = dist == null ? null : (int) Math.max(1, Math.round(dist / 1000.0 / ASSUMED_KMH * 60));
            out.add(new Suggestion(c.helperId(), c.name(), dist == null ? null : Math.round(dist * 10) / 10.0, eta, c.activeAssignments(), fresh, age));
        }
        // Each assignment a helper already holds counts as an extra 5 km, so a free helper a little farther away beats a busy one next door.
        // Helpers with no location go last.
        out.sort(Comparator.comparingDouble((Suggestion s) -> s.distanceMeters() == null ? Double.MAX_VALUE : s.distanceMeters() + s.activeAssignments() * BUSY_PENALTY_METERS)
                .thenComparing(Suggestion::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }
}
