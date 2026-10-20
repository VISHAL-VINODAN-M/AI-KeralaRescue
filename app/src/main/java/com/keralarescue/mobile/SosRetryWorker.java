package com.keralarescue.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SosRetryWorker extends Worker {
    public SosRetryWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    @NonNull @Override public Result doWork() {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("kerala_rescue", Context.MODE_PRIVATE);
        synchronized ("kerala_rescue_queue".intern()) {
            String endpoint = prefs.getString("backend", BuildConfig.API_BASE_URL);
            String stored = prefs.getString("pending", "[]");
            JSONArray queued;
            try { queued = new JSONArray(stored); }
            catch (Exception e) { queued = migrateOldQueue(stored); }
            JSONArray remaining = new JSONArray();
            boolean failed = false;
            for (int i = 0; i < queued.length(); i++) {
                JSONObject request = queued.optJSONObject(i);
                if (request == null || !post(endpoint, request)) {
                    if (request != null) remaining.put(request);
                    failed = true;
                }
            }
            prefs.edit().putString("pending", remaining.toString()).apply();
            return failed ? Result.retry() : Result.success();
        }
    }

    private JSONArray migrateOldQueue(String stored) {
        JSONArray migrated = new JSONArray();
        if (stored == null) return migrated;
        for (String item : stored.split(";")) {
            String[] fields = item.split("\\|", -1);
            if (fields.length < 1 || fields[0].isEmpty()) continue;
            try {
                JSONObject request = new JSONObject();
                request.put("emergencyId", fields[0]);
                request.put("trackingToken", java.util.UUID.randomUUID().toString().replace("-", ""));
                request.put("emergencyType", "CRITICAL_SOS");
                request.put("priority", "CRITICAL");
                request.put("status", "REQUESTED");
                boolean hasLocation = fields.length > 2 && !fields[1].isEmpty() && !fields[2].isEmpty();
                if (hasLocation) {
                    double lat = Double.parseDouble(fields[1]), lon = Double.parseDouble(fields[2]);
                    request.put("location", lat + "," + lon);
                    request.put("latitude", lat); request.put("longitude", lon);
                } else {
                    request.put("location", "GPS unavailable");
                    request.put("latitude", JSONObject.NULL); request.put("longitude", JSONObject.NULL);
                }
                request.put("description", "Critical SOS from Android app");
                request.put("phone", ""); request.put("assignedTeam", "");
                request.put("critical", true); request.put("locationUnavailable", !hasLocation);
                migrated.put(request);
            } catch (Exception ignored) { }
        }
        return migrated;
    }

    private boolean post(String endpoint, JSONObject request) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
