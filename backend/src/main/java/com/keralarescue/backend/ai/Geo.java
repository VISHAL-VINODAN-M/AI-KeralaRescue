package com.keralarescue.backend.ai;

/** Small geographic helpers. */
public final class Geo {
    private Geo() { }

    /** Great-circle distance in metres. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static boolean validCoordinates(Double lat, Double lon) {
        return lat != null && lon != null && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !(lat == 0 && lon == 0);
    }
}
