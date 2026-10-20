package com.keralarescue.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Tells the server which phone to push to for the citizen's active request. Does nothing when this build has no
 * google-services.json (push is optional), and never affects the SOS flow.
 */
final class PushRegistrar {
    private PushRegistrar() { }

    /** @param endpoint the emergencies URL, e.g. https://host/api/emergencies */
    static void register(Context context, String endpoint) {
        try {
            if (FirebaseApp.getApps(context).isEmpty() || endpoint == null || !endpoint.contains("/")) return;
            SharedPreferences prefs = context.getSharedPreferences("kerala_rescue", Context.MODE_PRIVATE);
            final String id = prefs.getString("last_request_id", "");
            final String tracking = prefs.getString("last_tracking_token", "");
            if (id.isEmpty() || tracking.isEmpty()) return;
            final String devices = endpoint.substring(0, endpoint.lastIndexOf('/')) + "/devices";
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
                if (token == null || (id + ":" + token).equals(prefs.getString("push_registered", ""))) return;
                new Thread(() -> post(prefs, devices, id, tracking, token)).start();
            });
        } catch (RuntimeException ignored) { /* push is best effort */ }
    }

    private static void post(SharedPreferences prefs, String url, String id, String tracking, String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Tracking-Token", tracking);
            byte[] body = new JSONObject().put("token", token).put("emergencyId", id).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            int code = c.getResponseCode();
            // 2xx = registered. 401 usually means the SOS has not reached the server yet; we try again on the next status check.
            if (code >= 200 && code < 300) prefs.edit().putString("push_registered", id + ":" + token).apply();
        } catch (Exception ignored) {
            // offline or server down: retried on the next status check
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
