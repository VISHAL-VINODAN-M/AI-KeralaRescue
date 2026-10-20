package com.keralarescue.backend.notify;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Sends SMS through the Twilio REST API. Does nothing (returns false) until the three settings are provided. */
public class SmsClient {
    private static final Logger LOG = Logger.getLogger(SmsClient.class.getName());
    private final String sid, token, from, baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public SmsClient(String sid, String token, String from, String baseUrl) {
        this.sid = sid == null ? "" : sid.trim();
        this.token = token == null ? "" : token.trim();
        this.from = from == null ? "" : from.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? "https://api.twilio.com" : baseUrl).replaceAll("/+$", "");
    }

    public boolean isConfigured() { return !sid.isEmpty() && !token.isEmpty() && !from.isEmpty(); }

    /** Converts a typed Indian number such as 98470 12345 or 09847012345 into +919847012345. Returns null if it is not plausible. */
    public static String normalize(String phone) {
        if (phone == null) return null;
        String p = phone.trim();
        boolean plus = p.startsWith("+");
        String d = p.replaceAll("\\D", "");
        if (plus) return d.length() >= 10 && d.length() <= 15 ? "+" + d : null;
        if (d.length() == 10) return "+91" + d;
        if (d.length() == 11 && d.startsWith("0")) return "+91" + d.substring(1);
        if (d.length() == 12 && d.startsWith("91")) return "+" + d;
        return null;
    }

    public boolean send(String to, String body) {
        String number = normalize(to);
        if (!isConfigured() || number == null || body == null || body.isBlank()) return false;
        try {
            String form = "To=" + enc(number) + "&From=" + enc(from) + "&Body=" + enc(body.length() > 600 ? body.substring(0, 600) : body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/2010-04-01/Accounts/" + sid + "/Messages.json"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 == 2) return true;
            LOG.log(Level.WARNING, "SMS gateway returned HTTP {0}", r.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "SMS send failed: {0}", e.toString());
        }
        return false;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
