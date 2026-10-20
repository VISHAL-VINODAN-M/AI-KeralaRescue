package com.keralarescue.backend.notify;

import java.util.Locale;

/** Wording of SMS and push messages. Push bodies carry English and Malayalam; SMS stays English (Malayalam SMS costs several times more). */
public final class Templates {
    private Templates() { }

    public record Text(String title, String sms, String push) { }

    /** Message for the citizen when a responder or helper moves their request forward; null if no message is warranted. */
    public static Text citizenStatus(String requestId, String status, String helperName) {
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        String who = helperName == null || helperName.isBlank() ? "A helper" : helperName.trim();
        return switch (s) {
            case "HELPER ASSIGNED" -> new Text("Help is assigned",
                    "Kerala Rescue: " + who + " has been assigned to your request " + requestId + ".",
                    who + " has been assigned to your request. / നിങ്ങളുടെ അഭ്യർത്ഥനയ്ക്ക് ഒരു സഹായിയെ നിയോഗിച്ചിട്ടുണ്ട്.");
            case "HELPER ON THE WAY" -> new Text("Helper on the way",
                    "Kerala Rescue: " + who + " is on the way to you. Request " + requestId + ".",
                    who + " is on the way. / സഹായി നിങ്ങളുടെ അടുത്തേക്ക് വരുന്നു.");
            case "HELPER ARRIVED" -> new Text("Helper has arrived",
                    "Kerala Rescue: " + who + " has arrived. Request " + requestId + ".",
                    who + " has arrived. / സഹായി എത്തിയിരിക്കുന്നു.");
            case "RESCUE IN PROGRESS" -> new Text("Rescue in progress",
                    null, "Rescue is in progress. / രക്ഷാപ്രവർത്തനം നടക്കുന്നു.");
            case "RESOLVED" -> new Text("Request completed",
                    "Kerala Rescue: your request " + requestId + " is marked complete. Stay safe.",
                    "Your request is marked complete. / നിങ്ങളുടെ അഭ്യർത്ഥന പൂർത്തിയായി.");
            default -> null;
        };
    }

    public static String helperAssignmentSms(String requestId, String type, String priority, int people, String description,
                                              Double lat, Double lon, String helperPageUrl) {
        StringBuilder sb = new StringBuilder("Kerala Rescue: new assignment ").append(requestId).append(" (")
                .append(type).append(", ").append(priority).append(", ").append(people).append(people == 1 ? " person" : " people").append(").");
        String d = description == null ? "" : description.trim().replaceAll("\\s+", " ");
        if (!d.isEmpty()) sb.append(' ').append(d.length() > 100 ? d.substring(0, 100) + "..." : d);
        if (lat != null && lon != null) sb.append(" Map: https://maps.google.com/?q=").append(lat).append(',').append(lon);
        if (helperPageUrl != null && !helperPageUrl.isBlank()) sb.append(" Open: ").append(helperPageUrl);
        return sb.toString();
    }
}
