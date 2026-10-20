package com.keralarescue.backend.ai;

import com.keralarescue.backend.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal client for the Anthropic Messages API. Runs only on the server so the key never reaches a phone. */
public class ClaudeClient {
    public static class AiException extends Exception {
        public AiException(String message) { super(message); }
        public AiException(String message, Throwable cause) { super(message, cause); }
    }

    public record Turn(String role, String text) { }

    private static final List<String> IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private final String apiKey, model, baseUrl;
    private final HttpClient http;
    private final Duration timeout;

    public ClaudeClient(String apiKey, String model, String baseUrl, Duration timeout) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl).replaceAll("/+$", "");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    public boolean isConfigured() { return !apiKey.isEmpty(); }

    /** Sends a conversation and returns the text of the reply. The optional image is attached to the last user turn. */
    public String complete(String system, List<Turn> turns, int maxTokens, byte[] image, String imageContentType) throws AiException {
        if (!isConfigured()) throw new AiException("AI is not configured");
        List<Object> messages = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            boolean last = i == turns.size() - 1;
            Object content = t.text();
            String type = imageContentType == null ? "" : imageContentType.trim().toLowerCase();
            if (last && "user".equals(t.role()) && image != null && image.length > 0 && IMAGE_TYPES.contains(type)) {
                content = List.of(
                        Map.of("type", "image", "source", Map.of("type", "base64", "media_type", type,
                                "data", Base64.getEncoder().encodeToString(image))),
                        Map.of("type", "text", "text", t.text()));
            }
            messages.add(Map.of("role", t.role(), "content", content));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (system != null && !system.isBlank()) body.put("system", system);
        body.put("messages", messages);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw new AiException("AI service returned HTTP " + response.statusCode());
            Map<String, Object> parsed = Json.object(response.body());
            StringBuilder text = new StringBuilder();
            for (Object block : Json.list(parsed, "content")) {
                Map<String, Object> b = Json.map(block);
                if (b != null && "text".equals(b.get("type"))) text.append(Json.str(b, "text"));
            }
            if (text.length() == 0) throw new AiException("AI service returned no text");
            return text.toString().trim();
        } catch (IOException e) {
            throw new AiException("AI service unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("AI request interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new AiException("AI service sent an unreadable reply", e);
        }
    }
}
