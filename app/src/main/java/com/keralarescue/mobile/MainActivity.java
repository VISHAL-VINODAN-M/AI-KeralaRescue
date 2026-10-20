package com.keralarescue.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "kerala_rescue";
    private static final String DEFAULT_BACKEND = BuildConfig.API_BASE_URL;
    private FusedLocationProviderClient locationClient;
    private EditText smsNumber, phoneNumber, backendUrl, requestDescription, peopleCount, messageInput;
    private Spinner emergencyTypeSpinner, prioritySpinner;
    private ChipGroup conditionsGroup;
    private TextView result, networkStatus, teamStatus, helpCountStatus, statusTimeline, currentLocationInfo;
    private TextView messagesView;
    private ImageView imagePreview;
    private View activeCard, activeActions, requestForm, settingsBody, dismissStatusButton;
    private ScrollView mainScroll;
    private SharedPreferences prefs;
    private Uri selectedImage;
    private Bitmap selectedPhotoBitmap;
    private boolean sosInProgress;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusPoller = new Runnable() {
        @Override public void run() {
            if (prefs != null && !prefs.getString("last_request_id", "").isEmpty()) refreshTeamStatus(false);
            statusHandler.postDelayed(this, 20000L);
        }
    };
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> triggerAfterPermissions());
    private final ActivityResultLauncher<String> imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                selectedImage = uri;
                selectedPhotoBitmap = null;
                if (uri != null) {
                    imagePreview.setImageURI(uri);
                    imagePreview.setVisibility(View.VISIBLE);
                    ((Button) findViewById(R.id.addImageButton)).setText("Change photo");
                }
            });
    private final ActivityResultLauncher<Void> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicturePreview(), bitmap -> {
                if (bitmap != null) {
                    selectedPhotoBitmap = bitmap;
                    selectedImage = null;
                    imagePreview.setImageBitmap(bitmap);
                    imagePreview.setVisibility(View.VISIBLE);
                    ((Button) findViewById(R.id.addImageButton)).setText("Gallery");
                }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        smsNumber = findViewById(R.id.smsNumber);
        phoneNumber = findViewById(R.id.phoneNumber);
        backendUrl = findViewById(R.id.backendUrl);
        result = findViewById(R.id.result);
        networkStatus = findViewById(R.id.networkStatus);
        currentLocationInfo = findViewById(R.id.currentLocationInfo);
        mainScroll = findViewById(R.id.mainScroll);
        teamStatus = findViewById(R.id.teamStatus);
        statusTimeline = findViewById(R.id.statusTimeline);
        helpCountStatus = findViewById(R.id.helpCountStatus);
        requestDescription = findViewById(R.id.requestDescription);
        peopleCount = findViewById(R.id.peopleCount);
        messageInput = findViewById(R.id.messageInput);
        messagesView = findViewById(R.id.messagesView);
        emergencyTypeSpinner = findViewById(R.id.emergencyTypeSpinner);
        prioritySpinner = findViewById(R.id.prioritySpinner);
        conditionsGroup = findViewById(R.id.conditionsGroup);
        imagePreview = findViewById(R.id.imagePreview);
        activeCard = findViewById(R.id.activeCard);
        activeActions = findViewById(R.id.activeActions);
        requestForm = findViewById(R.id.requestForm);
        settingsBody = findViewById(R.id.settingsBody);
        dismissStatusButton = findViewById(R.id.dismissStatusButton);
        smsNumber.setText(prefs.getString("sms", ""));
        phoneNumber.setText(prefs.getString("phone", ""));
        backendUrl.setText(DEFAULT_BACKEND);
        updateNetworkStatus();
        findViewById(R.id.sosButton).setOnClickListener(v -> requestPermissionsAndTrigger());
        findViewById(R.id.addImageButton).setOnClickListener(v -> imagePicker.launch("image/*"));
        findViewById(R.id.takePhotoButton).setOnClickListener(v -> cameraLauncher.launch(null));
        findViewById(R.id.sendRequestButton).setOnClickListener(v -> submitRescueRequest());
        findViewById(R.id.refreshStatusButton).setOnClickListener(v -> refreshTeamStatus());
        findViewById(R.id.cancelEmergencyButton).setOnClickListener(v -> confirmCancelEmergency());
        findViewById(R.id.sendMessageButton).setOnClickListener(v -> sendMessage());
        findViewById(R.id.historyButton).setOnClickListener(v -> showEmergencyHistory());
        bindEmergencyCategory(R.id.categoryFire, "Fire");
        bindEmergencyCategory(R.id.categoryFlood, "Flood");
        bindEmergencyCategory(R.id.categoryQuake, "Earthquake");
        bindEmergencyCategory(R.id.categoryCrime, "Crime");
        bindEmergencyCategory(R.id.categoryAccident, "Accident");
        findViewById(R.id.categoryOther).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Other emergency")
                .setItems(new String[]{"Medical", "Person trapped", "Other"}, (dialog, which) -> {
                    String[] options = {"Medical", "Person trapped", "Other"};
                    selectEmergencyCategory((TextView) v, options[which]);
                }).show());
        findViewById(R.id.navMap).setOnClickListener(v -> openLocationMap());
        findViewById(R.id.navMessages).setOnClickListener(v -> openMessages());
        findViewById(R.id.navSos).setOnClickListener(v -> mainScroll.fullScroll(View.FOCUS_UP));
        findViewById(R.id.toggleRequestButton).setOnClickListener(v -> {
            boolean open = requestForm.getVisibility() != View.VISIBLE;
            requestForm.setVisibility(open ? View.VISIBLE : View.GONE);
            ((Button) v).setText(open ? "Close emergency options" : "Choose an emergency");
            if (open) mainScroll.post(() -> mainScroll.smoothScrollTo(0, requestForm.getTop()));
        });
        findViewById(R.id.toggleSettingsButton).setOnClickListener(v -> {
            settingsBody.setVisibility(settingsBody.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (settingsBody.getVisibility() == View.VISIBLE) mainScroll.post(() -> mainScroll.smoothScrollTo(0, settingsBody.getBottom()));
        });
        dismissStatusButton.setOnClickListener(v -> {
            prefs.edit().remove("last_status").apply();
            statusTimeline.setText("");
            messagesView.setText("");
            syncActiveCard(false);
        });
        // First run: show settings so the SMS number and phone number get filled in.
        if (prefs.getString("sms", "").isEmpty() && prefs.getString("phone", "").isEmpty()) settingsBody.setVisibility(View.VISIBLE);
        String savedId = prefs.getString("last_request_id", "");
        if (!savedId.isEmpty()) {
            helpCountStatus.setText("People in your request: " + prefs.getString("last_people_count", "—"));
            teamStatus.setText("Request " + savedId + " · Checking for team update…");
        }
        scheduleRetryWorker();
        createNotificationChannel();
        syncActiveCard(false);
        if (!savedId.isEmpty()) refreshTeamStatus();
    }

    // The "Your request" card only appears when there is something to show.
    private void syncActiveCard(boolean force) {
        boolean active = !prefs.getString("last_request_id", "").isEmpty();
        boolean finished = !prefs.getString("last_status", "").isEmpty();
        activeCard.setVisibility(active || finished || force ? View.VISIBLE : View.GONE);
        activeActions.setVisibility(active ? View.VISIBLE : View.GONE);
        dismissStatusButton.setVisibility(active ? View.GONE : View.VISIBLE);
    }

    private void bindEmergencyCategory(int viewId, String type) {
        findViewById(viewId).setOnClickListener(v -> selectEmergencyCategory((TextView) v, type));
    }

    private void selectEmergencyCategory(TextView selected, String type) {
        for (int id : new int[]{R.id.categoryFire, R.id.categoryFlood, R.id.categoryQuake,
                R.id.categoryCrime, R.id.categoryAccident, R.id.categoryOther}) {
            findViewById(id).setSelected(id == selected.getId());
        }
        for (int i = 0; i < emergencyTypeSpinner.getCount(); i++) {
            if (type.equalsIgnoreCase(String.valueOf(emergencyTypeSpinner.getItemAtPosition(i)))) {
                emergencyTypeSpinner.setSelection(i);
                break;
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void openLocationMap() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            Toast.makeText(this, "Send an SOS first to allow location access, or enable Location in Settings.", Toast.LENGTH_LONG).show();
            return;
        }
        locationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location == null) {
                Toast.makeText(this, "Location is not available yet. Turn on Location and try again.", Toast.LENGTH_LONG).show();
                return;
            }
            String coordinates = String.format(Locale.US, "%.6f,%.6f", location.getLatitude(), location.getLongitude());
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + coordinates + "?q=" + coordinates))); }
            catch (Exception e) { Toast.makeText(this, "No map app is available on this phone.", Toast.LENGTH_LONG).show(); }
        }).addOnFailureListener(error -> Toast.makeText(this, "Could not read the current location.", Toast.LENGTH_LONG).show());
    }

    private void openMessages() {
        if (activeCard.getVisibility() != View.VISIBLE || activeActions.getVisibility() != View.VISIBLE) {
            Toast.makeText(this, "Send an SOS or help request before messaging the rescue team.", Toast.LENGTH_LONG).show();
            return;
        }
        mainScroll.post(() -> {
            mainScroll.smoothScrollTo(0, activeCard.getTop());
            messageInput.requestFocus();
        });
    }

    private void requestPermissionsAndTrigger() {
        saveSettings();
        String activeId = prefs.getString("last_request_id", "");
        if (!activeId.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("You already have an active emergency")
                    .setMessage("Request " + activeId + " is active. Open its latest rescue team update?")
                    .setPositiveButton("Open existing", (dialog, which) -> refreshTeamStatus())
                    .setNegativeButton("Keep emergency active", null).show();
            return;
        }
        if (sosInProgress) { result.setText("Preparing your emergency request…"); return; }
        sosInProgress = true;
        boolean loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean sms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        if (!loc || !sms || (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            if (Build.VERSION.SDK_INT >= 33) permissions.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.SEND_SMS, Manifest.permission.POST_NOTIFICATIONS});
            else permissions.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.SEND_SMS});
        }
        else triggerAfterPermissions();
    }

    private void triggerAfterPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission is required for SOS.", Toast.LENGTH_LONG).show();
            sosInProgress = false;
            return;
        }
        result.setText("Getting your location…");
        // Never let a missing GPS fix block an emergency: wait a few seconds, then use the
        // last known location, and finally send without a location.
        final AtomicBoolean done = new AtomicBoolean(false);
        final CancellationTokenSource cts = new CancellationTokenSource();
        final Runnable timeout = () -> { cts.cancel(); fallbackToLastKnown(done); };
        statusHandler.postDelayed(timeout, 8000L);
        fetchCurrentLocation(cts, done, timeout);
    }

    @SuppressLint("MissingPermission")
    private void fetchCurrentLocation(CancellationTokenSource cts, AtomicBoolean done, Runnable timeout) {
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(location -> {
                        statusHandler.removeCallbacks(timeout);
                        if (location == null) fallbackToLastKnown(done);
                        else deliverOnce(done, location.getLatitude(), location.getLongitude());
                    })
                    .addOnFailureListener(e -> { statusHandler.removeCallbacks(timeout); fallbackToLastKnown(done); });
        } catch (Exception e) { statusHandler.removeCallbacks(timeout); fallbackToLastKnown(done); }
    }

    @SuppressLint("MissingPermission")
    private void fallbackToLastKnown(AtomicBoolean done) {
        if (done.get()) return;
        try {
            locationClient.getLastLocation()
                    .addOnSuccessListener(l -> { if (l == null) deliverOnce(done, null, null); else deliverOnce(done, l.getLatitude(), l.getLongitude()); })
                    .addOnFailureListener(e -> deliverOnce(done, null, null));
        } catch (Exception e) { deliverOnce(done, null, null); }
    }

    private void deliverOnce(AtomicBoolean done, Double lat, Double lon) {
        if (!done.compareAndSet(false, true)) return;
        String locationText = lat == null || lon == null ? "Location unavailable · turn on Location services" :
                String.format(Locale.US, "%.5f, %.5f · included with your SOS", lat, lon);
        runOnUiThread(() -> currentLocationInfo.setText(locationText));
        handleEmergency(lat, lon);
    }

    // Try the configured server directly. Android's "validated internet" flag can be false
    // on a Wi-Fi LAN that still reaches the local rescue server.
    private void handleEmergency(Double lat, Double lon) {
        saveSettings();
        final String id = newEmergencyId();
        final JSONObject sos = makeRequestPayload(id, "CRITICAL_SOS", "Critical SOS from Android app", 1, lat, lon, null);
        prefs.edit().putString("last_request_id", id).putString("last_people_count", "1")
                .putString("last_tracking_token", sos.optString("trackingToken", "")).apply();
        rememberTrackingToken(sos.optString("trackingToken", ""));
        final String endpoint = backendUrl.getText().toString().trim();
        runOnUiThread(() -> result.setText("Sending SOS to rescue server…"));
        executor.execute(() -> {
            boolean sentOnline = postToServer(endpoint, sos);
            boolean sentSms = false;
            if (!sentOnline) sentSms = sendSms(id, lat, lon);
            if (!sentOnline) enqueuePending(sos); // always keep trying the server, even if SMS went out
            final boolean delivered = sentOnline, viaSms = sentSms;
            runOnUiThread(() -> {
                sosInProgress = false;
                syncActiveCard(false);
                if (delivered) result.setText("SOS sent to rescue server. ID: " + id);
                else if (viaSms) result.setText("SOS sent by SMS. It will also reach the rescue server when internet returns. ID: " + id);
                else result.setText("SOS saved on this phone. It will send automatically when internet returns. ID: " + id);
            });
            if (!delivered) scheduleRetryWorker();
        });
    }

    private void submitRescueRequest() {
        saveSettings();
        String activeId = prefs.getString("last_request_id", "");
        if (!activeId.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("You already have an active emergency")
                    .setMessage("Request " + activeId + " is still active. Open its latest update instead of creating another request?")
                    .setPositiveButton("Open existing", (dialog, which) -> refreshTeamStatus())
                    .setNegativeButton("Keep emergency active", null).show();
            return;
        }
        String description = requestDescription.getText().toString().trim();
        String peopleText = peopleCount.getText().toString().trim();
        int people;
        try { people = Integer.parseInt(peopleText); }
        catch (Exception e) { peopleCount.setError("Enter the number of people"); return; }
        if (people < 1 || people > 10000) { peopleCount.setError("Enter a number from 1 to 10000"); return; }
        final String id = newEmergencyId();
        final int reportedPeople = people;
        final Uri requestImage = selectedImage;
        final Bitmap requestBitmap = selectedPhotoBitmap;
        final String selectedType = emergencyTypeSpinner.getSelectedItem().toString();
        final String selectedPriority = prioritySpinner.getSelectedItem().toString();
        final String selectedConditions = selectedConditions();
        final int reportedInjuries = ((Chip) findViewById(R.id.conditionInjured)).isChecked() ? 1 : 0;
        findViewById(R.id.sendRequestButton).setEnabled(false);
        teamStatus.setText("Getting location and sending your request…");
        final String endpoint = backendUrl.getText().toString().trim();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationClient.getLastLocation().addOnSuccessListener(location -> beginRescueRequest(id, description, reportedPeople,
                    requestImage, requestBitmap, endpoint, selectedType, selectedPriority, selectedConditions, reportedInjuries,
                    location == null ? null : location.getLatitude(), location == null ? null : location.getLongitude()))
                    .addOnFailureListener(error -> beginRescueRequest(id, description, reportedPeople, requestImage, requestBitmap,
                            endpoint, selectedType, selectedPriority, selectedConditions, reportedInjuries, null, null));
        } else {
            beginRescueRequest(id, description, reportedPeople, requestImage, requestBitmap, endpoint, selectedType,
                    selectedPriority, selectedConditions, reportedInjuries, null, null);
        }
    }

    private String selectedConditions() {
        StringBuilder result = new StringBuilder();
        for (int id : conditionsGroup.getCheckedChipIds()) {
            Chip chip = conditionsGroup.findViewById(id);
            if (chip == null) continue;
            if (result.length() > 0) result.append(", ");
            result.append(chip.getText());
        }
        return result.toString();
    }

    private void beginRescueRequest(String id, String description, int people, Uri requestImage, Bitmap requestBitmap,
                                   String endpoint, String type, String priority, String conditions, int injured,
                                   Double lat, Double lon) {
        executor.execute(() -> {
            String image = readImageBase64(requestImage, requestBitmap);
            if ((requestImage != null || requestBitmap != null) && image == null) {
                runOnUiThread(() -> {
                    findViewById(R.id.sendRequestButton).setEnabled(true);
                    teamStatus.setText("Could not read the selected photo. Please choose it again and resend.");
                });
                return;
            }
            JSONObject payload = makeRequestPayload(id, type, description, people, lat, lon, image, priority, conditions, injured);
            boolean sent = postToServer(endpoint, payload);
            if (!sent) enqueuePending(payload);
            prefs.edit().putString("last_request_id", id).putString("last_people_count", String.valueOf(people))
                    .putString("last_tracking_token", payload.optString("trackingToken", "")).apply();
            rememberTrackingToken(payload.optString("trackingToken", ""));
            runOnUiThread(() -> {
                findViewById(R.id.sendRequestButton).setEnabled(true);
                syncActiveCard(false);
                requestForm.setVisibility(View.GONE);
                ((Button) findViewById(R.id.toggleRequestButton)).setText("Choose an emergency");
                helpCountStatus.setText("People in your request: " + people);
                teamStatus.setText(sent ? "Request sent · ID: " + id + " · Refresh for team updates." :
                        "Request saved · ID: " + id + " · It will send when internet returns.");
                requestDescription.setText("");
                peopleCount.setText("");
                conditionsGroup.clearCheck();
                selectedImage = null;
                selectedPhotoBitmap = null;
                imagePreview.setImageDrawable(null);
                imagePreview.setVisibility(View.GONE);
                ((Button) findViewById(R.id.addImageButton)).setText("Gallery");
            });
            if (!sent) scheduleRetryWorker();
        });
    }

    private JSONObject makeRequestPayload(String id, String type, String description, int people,
                                          Double lat, Double lon, String imageBase64) {
        String priority = type.equals("CRITICAL_SOS") ? "CRITICAL" : "HIGH";
        return makeRequestPayload(id, type, description, people, lat, lon, imageBase64, priority, "", 0);
    }

    private JSONObject makeRequestPayload(String id, String type, String description, int people,
                                          Double lat, Double lon, String imageBase64, String priority,
                                          String conditions, int injured) {
        JSONObject body = new JSONObject();
        try {
            body.put("emergencyId", id);
            body.put("trackingToken", UUID.randomUUID().toString().replace("-", ""));
            body.put("emergencyType", type);
            body.put("priority", priority);
            body.put("status", type.equals("CRITICAL_SOS") ? "SOS SENT" : "RECEIVED");
            body.put("location", lat == null ? "Location unavailable" : lat + "," + lon);
            body.put("description", description);
            body.put("phone", prefs.getString("phone", "")); body.put("assignedTeam", "");
            body.put("latitude", lat == null ? JSONObject.NULL : lat);
            body.put("longitude", lon == null ? JSONObject.NULL : lon);
            body.put("critical", priority.equalsIgnoreCase("CRITICAL"));
            body.put("locationUnavailable", lat == null);
            body.put("peopleNeedingHelp", people);
            body.put("conditionDetails", conditions);
            body.put("injuredCount", injured);
            if (imageBase64 != null) body.put("imageBase64", imageBase64);
            body.put("imageContentType", imageBase64 == null ? JSONObject.NULL : "image/jpeg");
        } catch (JSONException ignored) { }
        return body;
    }

    private boolean postToServer(String endpoint, JSONObject body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) { return false; }
        finally { if (c != null) c.disconnect(); }
    }

    private void enqueuePending(JSONObject body) {
        synchronized ("kerala_rescue_queue".intern()) {
            JSONArray queue = readPendingQueue(prefs.getString("pending", "[]"));
            queue.put(body);
            prefs.edit().putString("pending", queue.toString()).apply();
        }
    }

    private JSONArray readPendingQueue(String stored) {
        try { return new JSONArray(stored); }
        catch (JSONException ignored) {
            JSONArray migrated = new JSONArray();
            if (stored == null) return migrated;
            for (String item : stored.split(";")) {
                String[] fields = item.split("\\|", -1);
                if (fields.length < 1 || fields[0].isEmpty()) continue;
                try {
                    JSONObject old = makeRequestPayload(fields[0], "CRITICAL_SOS", "Critical SOS from Android app", 1,
                            fields.length > 1 && !fields[1].isEmpty() ? Double.parseDouble(fields[1]) : null,
                            fields.length > 2 && !fields[2].isEmpty() ? Double.parseDouble(fields[2]) : null, null);
                    migrated.put(old);
                } catch (Exception ignoredParse) { }
            }
            return migrated;
        }
    }

    private boolean isPending(String id) {
        synchronized ("kerala_rescue_queue".intern()) {
            JSONArray queue = readPendingQueue(prefs.getString("pending", "[]"));
            for (int i = 0; i < queue.length(); i++)
                if (id.equals(queue.optJSONObject(i) == null ? "" : queue.optJSONObject(i).optString("emergencyId"))) return true;
        }
        return false;
    }

    // Opens a connection that carries this request's private tracking token.
    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        String token = prefs.getString("last_tracking_token", "");
        if (!token.isEmpty()) c.setRequestProperty("X-Tracking-Token", token);
        return c;
    }

    private void refreshTeamStatus() { refreshTeamStatus(true); }

    private void refreshTeamStatus(boolean showProgress) {
        saveSettings();
        PushRegistrar.register(this, backendUrl.getText().toString().trim());
        String id = prefs.getString("last_request_id", "");
        if (id.isEmpty()) { teamStatus.setText("Send a request to see updates here."); return; }
        if (showProgress) teamStatus.setText("Checking for a rescue team update…");
        final String endpoint = backendUrl.getText().toString().trim();
        executor.execute(() -> {
            HttpURLConnection c = null;
            String message;
            boolean terminal = false;
            String currentStatus = "";
            try {
                String encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8.name());
                c = open(endpoint + "/" + encodedId);
                c.setRequestMethod("GET"); c.setConnectTimeout(7000); c.setReadTimeout(7000);
                int code = c.getResponseCode();
                if (code == 404 && isPending(id)) {
                    runOnUiThread(() -> teamStatus.setText("Request " + id + " is saved on this phone and will reach the rescue server when internet returns."));
                    return;
                }
                if (code == 404) {
                    prefs.edit().remove("last_request_id").remove("last_people_count").remove("last_status").apply();
                    runOnUiThread(() -> showArchivedStatus(id));
                    return;
                }
                if (code < 200 || code >= 300) throw new Exception("Server returned HTTP " + code);
                StringBuilder response = new StringBuilder();
                try (InputStream in = c.getInputStream()) {
                    byte[] buffer = new byte[2048]; int n;
                    while ((n = in.read(buffer)) != -1) response.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                }
                JSONObject data = new JSONObject(response.toString());
                String status = data.optString("status", "Update received");
                currentStatus = status;
                terminal = status.equalsIgnoreCase("RESOLVED") || status.equalsIgnoreCase("CANCELLED") || status.equalsIgnoreCase("COMPLETED");
                String team = data.optString("assignedTeam", "");
                String eta = data.optString("estimatedArrival", data.optString("eta", ""));
                message = status + (team.isEmpty() ? "" : " · Team: " + team) + (eta.isEmpty() ? "" : " · ETA: " + eta);
            } catch (Exception e) { message = "Could not reach the rescue server. Your request ID is " + id + ". Check your Wi-Fi and backend URL."; }
            finally { if (c != null) c.disconnect(); }
            final String update = message;
            final boolean isTerminal = terminal;
            final String statusValue = currentStatus;
            runOnUiThread(() -> {
                teamStatus.setText(update);
                if (!statusValue.isEmpty()) statusTimeline.setText(renderTimeline(statusValue));
                String previous = prefs.getString("last_status", "");
                if (!statusValue.isEmpty() && !statusValue.equals(previous)) {
                    if (!previous.isEmpty()) showStatusNotification(statusValue, id);
                    prefs.edit().putString("last_status", statusValue).apply();
                }
                if (isTerminal) prefs.edit().remove("last_request_id").remove("last_people_count").apply();
                syncActiveCard(false);
                loadMessages();
            });
        });
    }

    private String renderTimeline(String status) {
        if (status.equalsIgnoreCase("CANCELLED")) return "Emergency cancelled. If you are still in danger, send a new SOS.";
        if (status.equalsIgnoreCase("EXPIRED")) return "This request timed out after one hour and is now in your history. If you still need help, send a new SOS.";
        String[] stages = {"SOS SENT", "RECEIVED", "UNDER REVIEW", "HELPER ASSIGNED", "HELPER ON THE WAY", "HELPER ARRIVED", "RESCUE IN PROGRESS", "RESOLVED"};
        String normalized = status.toUpperCase(Locale.US);
        if (normalized.equals("REQUESTED")) normalized = "RECEIVED";
        if (normalized.equals("ACKNOWLEDGED")) normalized = "UNDER REVIEW";
        if (normalized.equals("TEAM_ASSIGNED")) normalized = "HELPER ASSIGNED";
        if (normalized.equals("IN_PROGRESS")) normalized = "RESCUE IN PROGRESS";
        int active = 0;
        for (int i = 0; i < stages.length; i++) if (stages[i].equals(normalized)) active = i;
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < stages.length; i++) dots.append(i <= active ? "●" : "○").append(i < stages.length - 1 ? " " : "");
        String name = stages[active].substring(0, 1) + stages[active].substring(1).toLowerCase(Locale.US);
        return dots + "\n" + name + "  ·  step " + (active + 1) + " of " + stages.length;
    }

    private void showArchivedStatus(String id) {
        String token = prefs.getString("last_tracking_token", "");
        if (token.isEmpty()) { teamStatus.setText("This emergency is no longer active. Its record is in your history."); syncActiveCard(true); return; }
        String endpoint = backendUrl.getText().toString().trim();
        int lastSlash = endpoint.lastIndexOf('/');
        String target = endpoint.substring(0, lastSlash) + "/history?trackingToken=" + Uri.encode(token);
        executor.execute(() -> {
            String archivedStatus = "";
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(target).openConnection();
                connection.setConnectTimeout(6000); connection.setReadTimeout(6000);
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    StringBuilder response = new StringBuilder();
                    try (InputStream in = connection.getInputStream()) {
                        byte[] buffer = new byte[2048]; int count;
                        while ((count = in.read(buffer)) != -1) response.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                    }
                    JSONArray history = new JSONArray(response.toString());
                    for (int i = 0; i < history.length(); i++) {
                        JSONObject record = history.getJSONObject(i);
                        if (id.equals(record.optString("emergencyId"))) { archivedStatus = record.optString("status"); break; }
                    }
                }
            } catch (Exception ignored) { }
            finally { if (connection != null) connection.disconnect(); }
            String status = archivedStatus;
            runOnUiThread(() -> {
                if (status.isEmpty()) { teamStatus.setText("This emergency is no longer active. Its record is in your history."); syncActiveCard(true); }
                else {
                    prefs.edit().putString("last_status", status).apply();
                    syncActiveCard(true);
                    teamStatus.setText("" + status + " · This request is now in your history.");
                    statusTimeline.setText(renderTimeline(status));
                    if (status.equalsIgnoreCase("CANCELLED")) statusTimeline.setText("Emergency cancelled. If you are still in danger, send a new SOS.");
                    showStatusNotification(status, id);
                }
            });
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("rescue_updates", "Rescue updates", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Status changes for your active rescue request");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void showStatusNotification(String status, String requestId) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, "rescue_updates")
                .setSmallIcon(R.drawable.ic_kerala_rescue).setContentTitle("Rescue team update")
                .setContentText(requestId + " · " + status).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true);
        getSystemService(NotificationManager.class).notify(2001, notification.build());
    }

    private void sendMessage() {
        String id = prefs.getString("last_request_id", "");
        String message = messageInput.getText().toString().trim();
        if (id.isEmpty()) { teamStatus.setText("There is no active emergency to message about."); return; }
        if (message.isEmpty()) { messageInput.setError("Enter a message"); return; }
        JSONObject body = new JSONObject();
        try { body.put("senderRole", "USER"); body.put("senderName", "Citizen"); body.put("message", message); }
        catch (JSONException ignored) { }
        String target = backendUrl.getText().toString().trim() + "/" + Uri.encode(id) + "/messages";
        executor.execute(() -> {
            String error = null;
            HttpURLConnection connection = null;
            try {
                connection = open(target);
                connection.setRequestMethod("POST"); connection.setConnectTimeout(7000); connection.setReadTimeout(7000);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) error = "Message could not be sent (HTTP " + code + ").";
            } catch (Exception e) { error = "Message could not be sent. Check the backend connection."; }
            finally { if (connection != null) connection.disconnect(); }
            final String failure = error;
            runOnUiThread(() -> {
                if (failure == null) { messageInput.setText(""); loadMessages(); }
                else teamStatus.setText(failure);
            });
        });
    }

    private void loadMessages() {
        String id = prefs.getString("last_request_id", "");
        if (id.isEmpty()) return;
        String target = backendUrl.getText().toString().trim() + "/" + Uri.encode(id) + "/messages";
        executor.execute(() -> {
            HttpURLConnection connection = null;
            StringBuilder lines = new StringBuilder();
            try {
                connection = open(target);
                connection.setConnectTimeout(6000); connection.setReadTimeout(6000);
                if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return;
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[2048]; int count;
                    while ((count = in.read(buffer)) != -1) lines.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                }
                JSONArray messages = new JSONArray(lines.toString());
                StringBuilder display = new StringBuilder();
                long newestMessageId = prefs.getLong("last_message_id", 0L);
                long maxMessageId = newestMessageId;
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject item = messages.getJSONObject(i);
                    long messageId = item.optLong("messageId", 0L);
                    if (messageId > maxMessageId) maxMessageId = messageId;
                    if (newestMessageId > 0 && messageId > newestMessageId && !"USER".equalsIgnoreCase(item.optString("senderRole")))
                        showStatusNotification("New message: " + item.optString("message", ""), id);
                    display.append(item.optString("senderName", item.optString("senderRole", "Team")))
                            .append(" · ").append(item.optString("createdAt", "")).append("\n")
                            .append(item.optString("message", "")).append("\n\n");
                }
                String rendered = display.toString().trim();
                prefs.edit().putLong("last_message_id", maxMessageId).apply();
                runOnUiThread(() -> messagesView.setText(rendered.isEmpty() ? "No messages yet." : rendered));
            } catch (Exception ignored) { }
            finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void showEmergencyHistory() {
        JSONArray tokens;
        try { tokens = new JSONArray(prefs.getString("history_tokens", "[]")); }
        catch (JSONException e) { tokens = new JSONArray(); }
        if (tokens.length() == 0) {
            String latest = prefs.getString("last_tracking_token", "");
            if (!latest.isEmpty()) tokens.put(latest);
        }
        if (tokens.length() == 0) { teamStatus.setText("No emergency history is saved on this phone yet."); return; }
        String endpoint = backendUrl.getText().toString().trim();
        int lastSlash = endpoint.lastIndexOf('/');
        String historyEndpoint = endpoint.substring(0, lastSlash) + "/history?trackingToken=";
        JSONArray historyTokens = tokens;
        executor.execute(() -> {
            String rendered = "No previous emergencies.";
            try {
                StringBuilder items = new StringBuilder();
                for (int t = 0; t < historyTokens.length(); t++) {
                    HttpURLConnection connection = null;
                    try {
                        String target = historyEndpoint + Uri.encode(historyTokens.getString(t));
                        connection = (HttpURLConnection) new URL(target).openConnection();
                        connection.setConnectTimeout(7000); connection.setReadTimeout(7000);
                        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) continue;
                        StringBuilder response = new StringBuilder();
                        try (InputStream in = connection.getInputStream()) {
                            byte[] buffer = new byte[2048]; int count;
                            while ((count = in.read(buffer)) != -1) response.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                        }
                        JSONArray history = new JSONArray(response.toString());
                        for (int i = 0; i < history.length(); i++) {
                            JSONObject entry = history.getJSONObject(i);
                            items.append(entry.optString("emergencyId")).append(" · ").append(entry.optString("emergencyType"))
                                    .append(" · ").append(entry.optString("priority")).append(" · ").append(entry.optString("status"))
                                    .append("\n").append(entry.optString("createdAt")).append("\n\n");
                        }
                    } finally { if (connection != null) connection.disconnect(); }
                }
                if (items.length() > 0) rendered = items.toString().trim();
            } catch (Exception e) { rendered = "Could not load history. Check the backend connection."; }
            String resultText = rendered;
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Emergency history")
                    .setMessage(resultText).setPositiveButton("Close", null).show());
        });
    }

    private void rememberTrackingToken(String token) {
        if (token == null || token.isEmpty()) return;
        synchronized ("kerala_rescue_history_tokens".intern()) {
            JSONArray tokens;
            try { tokens = new JSONArray(prefs.getString("history_tokens", "[]")); }
            catch (JSONException e) { tokens = new JSONArray(); }
            for (int i = 0; i < tokens.length(); i++) if (token.equals(tokens.optString(i))) return;
            tokens.put(token);
            prefs.edit().putString("history_tokens", tokens.toString()).apply();
        }
    }

    private void confirmCancelEmergency() {
        String id = prefs.getString("last_request_id", "");
        if (id.isEmpty()) { teamStatus.setText("There is no active emergency to cancel."); return; }
        new AlertDialog.Builder(this).setTitle("Are you sure you are safe?")
                .setMessage("Cancelling marks this request as cancelled and informs the rescue team.")
                .setPositiveButton("I'M SAFE", (dialog, which) -> updateEmergencyStatus(id, "CANCELLED"))
                .setNegativeButton("KEEP EMERGENCY ACTIVE", null).show();
    }

    private void updateEmergencyStatus(String id, String status) {
        String endpoint = backendUrl.getText().toString().trim() + "/" + Uri.encode(id) + "/status";
        JSONObject body = new JSONObject();
        try { body.put("status", status); }
        catch (JSONException ignored) { }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            int code = -1;
            try {
                connection = open(endpoint);
                connection.setRequestMethod("PATCH"); connection.setConnectTimeout(7000); connection.setReadTimeout(7000);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                code = connection.getResponseCode();
            } catch (Exception ignored) { }
            finally { if (connection != null) connection.disconnect(); }
            int resultCode = code;
            runOnUiThread(() -> {
                if (resultCode >= 200 && resultCode < 300) refreshTeamStatus();
                else teamStatus.setText("Could not update the request. Check the backend connection and try again.");
            });
        });
    }

    private String readImageBase64(Uri uri) {
        return readImageBase64(uri, null);
    }

    private String readImageBase64(Uri uri, Bitmap photo) {
        if (uri == null && photo == null) return null;
        try {
            Bitmap original;
            if (photo != null) original = photo;
            else try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) return null;
                original = BitmapFactory.decodeStream(input);
            }
            if (original == null) return null;
            int width = original.getWidth(), height = original.getHeight();
            float scale = Math.min(1f, 1280f / Math.max(width, height));
            Bitmap resized = scale < 1f ? Bitmap.createScaledBitmap(original, Math.round(width * scale), Math.round(height * scale), true) : original;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            resized.compress(Bitmap.CompressFormat.JPEG, 78, out);
            if (resized != original) { resized.recycle(); original.recycle(); }
            return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) { return null; }
    }

    private boolean sendSms(String id, Double lat, Double lon) {
        String number = smsNumber.getText().toString().trim();
        if (TextUtils.isEmpty(number) || ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false;
        String message = "KERALA RESCUE CRITICAL SOS! ID: " + id + ". Location: " +
                (lat == null ? "GPS unavailable" : "https://maps.google.com/?q=" + lat + "," + lon) + ". Please respond immediately.";
        try {
            SmsManager sms = Build.VERSION.SDK_INT >= 31 ? getSystemService(SmsManager.class) : SmsManager.getDefault();
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(message), null, null);
            return true;
        } catch (Exception e) { return false; }
    }

    private String newEmergencyId() {
        return "MOB-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "-" +
                UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.US);
    }

    private void saveSettings() {
        prefs.edit().putString("sms", smsNumber.getText().toString().trim())
                .putString("phone", phoneNumber.getText().toString().trim())
                .remove("backend").apply();
    }

    private void scheduleRetryWorker() {
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SosRetryWorker.class).setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueueUniqueWork("kerala-rescue-pending", ExistingWorkPolicy.APPEND_OR_REPLACE, req);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void updateNetworkStatus() {
        if (networkStatus == null) return;
        boolean online = isOnline();
        networkStatus.setText(online ? "● Online" : "● Offline · SOS uses SMS");
        networkStatus.setTextColor(ContextCompat.getColor(this, online ? R.color.green : R.color.amber));
        android.graphics.drawable.Drawable background = networkStatus.getBackground();
        if (background != null) {
            background.mutate().setTint(ContextCompat.getColor(this, online ? R.color.green_soft : R.color.amber_soft));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (networkStatus != null) updateNetworkStatus();
        PushRegistrar.register(this, backendUrl.getText().toString().trim());
        statusHandler.removeCallbacks(statusPoller);
        statusHandler.postDelayed(statusPoller, 20000L);
    }
    @Override protected void onPause() { statusHandler.removeCallbacks(statusPoller); super.onPause(); }
    @Override protected void onDestroy() { statusHandler.removeCallbacks(statusPoller); executor.shutdownNow(); super.onDestroy(); }
}
