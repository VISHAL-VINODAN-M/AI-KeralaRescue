package com.keralarescue.backend.notify;

import com.keralarescue.backend.util.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends Firebase Cloud Messaging (HTTP v1) notifications using a Google service-account key,
 * with no Firebase SDK. Set FIREBASE_SERVICE_ACCOUNT_JSON to the full key file contents.
 */
public class PushClient {
    public enum Result { SENT, INVALID_TOKEN, FAILED, DISABLED }

    private static final Logger LOG = Logger.getLogger(PushClient.class.getName());
    private final String projectId, clientEmail, tokenUri, fcmBase;
    private final PrivateKey key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private String accessToken;
    private long accessTokenExpiry;

    /** @param serviceAccountJson contents of the service-account JSON key, or blank to disable push. */
    public PushClient(String serviceAccountJson, String fcmBaseUrl) {
        String project = null, email = null, uri = null;
        PrivateKey parsed = null;
        if (serviceAccountJson != null && !serviceAccountJson.isBlank()) {
            try {
                Map<String, Object> m = Json.object(serviceAccountJson.trim());
                project = Json.str(m, "project_id");
                email = Json.str(m, "client_email");
                uri = Json.str(m, "token_uri");
                String pem = Json.str(m, "private_key");
                if (pem != null) {
                    byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
                    parsed = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "FIREBASE_SERVICE_ACCOUNT_JSON could not be read; push notifications are off: {0}", e.toString());
                project = null;
            }
        }
        this.projectId = project;
        this.clientEmail = email;
        this.tokenUri = uri == null ? "https://oauth2.googleapis.com/token" : uri;
        this.key = parsed;
        this.fcmBase = (fcmBaseUrl == null || fcmBaseUrl.isBlank() ? "https://fcm.googleapis.com" : fcmBaseUrl).replaceAll("/+$", "");
    }

    public boolean isConfigured() { return projectId != null && clientEmail != null && key != null; }

    public Result send(String deviceToken, String title, String body, Map<String, String> data) {
        if (!isConfigured()) return Result.DISABLED;
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("token", deviceToken);
            message.put("notification", Map.of("title", title, "body", body));
            message.put("data", data == null ? Map.of() : data);
            message.put("android", Map.of("priority", "HIGH"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(fcmBase + "/v1/projects/" + projectId + "/messages:send"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("message", message)), StandardCharsets.UTF_8)).build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 == 2) return Result.SENT;
            if (r.statusCode() == 404 || r.body().contains("UNREGISTERED") || r.body().contains("INVALID_ARGUMENT")) return Result.INVALID_TOKEN;
            LOG.log(Level.WARNING, "FCM returned HTTP {0}", r.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "Push send failed: {0}", e.toString());
        }
        return Result.FAILED;
    }

    private synchronized String accessToken() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        if (accessToken != null && now < accessTokenExpiry - 60) return accessToken;
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientEmail);
        claims.put("scope", "https://www.googleapis.com/auth/firebase.messaging");
        claims.put("aud", tokenUri);
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        String signingInput = header + "." + enc.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String assertion = signingInput + "." + enc.encodeToString(sig.sign());
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(tokenUri)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Google token endpoint returned HTTP " + r.statusCode());
        Map<String, Object> m = Json.object(r.body());
        accessToken = Json.str(m, "access_token");
        accessTokenExpiry = now + (m.get("expires_in") instanceof Number n ? n.longValue() : 3000);
        return accessToken;
    }
}
