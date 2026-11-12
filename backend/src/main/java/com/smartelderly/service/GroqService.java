package com.smartelderly.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelderly.config.AppProperties;
import com.smartelderly.model.DetectionDetails;
import com.smartelderly.model.Incident;
import com.smartelderly.model.IncidentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates the incident summary and caregiver recommendation with a Qwen model on Groq.
 * Called only after the ML service has produced a verified (or inconclusive) detection; it never performs detection.
 * Any failure falls back to deterministic text so the incident is always stored.
 */
@Service
public class GroqService {
    private static final Logger log = LoggerFactory.getLogger(GroqService.class);
    public static final String SOURCE_GROQ = "GROQ";
    public static final String SOURCE_FALLBACK = "FALLBACK";

    private static final String SYSTEM_PROMPT = """
            You write short, factual incident notes for an elderly fall-monitoring system used by caregivers.
            Rules:
            - Describe only what the detection data shows. A computer-vision system detected the event from video.
            - Do not diagnose injuries, do not invent medical conditions, and do not claim an injury occurred.
            - Use calm, factual, non-alarming language. No markdown, no bullet symbols.
            - "summary": 2-3 sentences describing what was detected, when, where, and how it was verified.
            - "recommendation": 2-3 practical sentences telling the caregiver what to do next
              (check on the resident in person, offer assistance, contact emergency services if the resident is
              unresponsive, in pain, or cannot get up, and record the outcome in the portal).
            Respond with JSON only: {"summary": "...", "recommendation": "..."}""";

    private final AppProperties.Groq cfg;
    private final ZoneId zone;
    private final ObjectMapper json;
    private final HttpClient http;

    public record AiResult(String summary, String recommendation, String source, String model) {}

    public GroqService(AppProperties props, ObjectMapper json) {
        this.cfg = props.groq();
        this.zone = ZoneId.of(props.timezone());
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean configured() { return cfg.configured(); }

    public AiResult analyze(Incident incident) {
        if (!cfg.configured()) {
            log.info("Groq is not configured; using fallback analysis for {}", incident.getIncidentId());
            return fallback(incident);
        }
        String prompt = describe(incident);
        try {
            AiResult result = call(prompt, true);
            return result != null ? result : fallback(incident);
        } catch (UnsupportedOptionsException e) {
            try {
                AiResult result = call(prompt, false);
                return result != null ? result : fallback(incident);
            } catch (Exception retry) {
                log.warn("Groq request failed for {}: {}", incident.getIncidentId(), retry.getMessage());
                return fallback(incident);
            }
        } catch (Exception e) {
            log.warn("Groq request failed for {}: {}", incident.getIncidentId(), e.getMessage());
            return fallback(incident);
        }
    }

    private AiResult call(String prompt, boolean structured) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", cfg.model());
        body.put("temperature", 0.2);
        body.put("max_completion_tokens", 600);
        body.put("messages", List.of(Map.of("role", "system", "content", SYSTEM_PROMPT), Map.of("role", "user", "content", prompt)));
        if (structured) {
            body.put("response_format", Map.of("type", "json_object"));
            if (cfg.model().toLowerCase(Locale.ROOT).contains("qwen")) body.put("reasoning_format", "hidden");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.url()))
                .timeout(Duration.ofSeconds(Math.max(5, cfg.timeoutSeconds())))
                .header("Authorization", "Bearer " + cfg.key())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 && structured) throw new UnsupportedOptionsException();
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Groq returned HTTP " + response.statusCode());
        }
        String content = json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText("");
        content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (content.isBlank()) throw new IllegalStateException("Groq returned an empty response");

        String summary;
        String recommendation;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        String jsonText = start >= 0 && end > start ? content.substring(start, end + 1) : content;
        try {
            JsonNode node = json.readTree(jsonText);
            summary = node.path("summary").asText("").trim();
            recommendation = node.path("recommendation").asText("").trim();
        } catch (Exception notJson) {
            String[] parts = content.split("(?i)recommendation\\s*:", 2);
            summary = parts[0].replaceFirst("(?i)^\\s*summary\\s*:", "").trim();
            recommendation = parts.length > 1 ? parts[1].trim() : "";
        }
        if (summary.isBlank() || recommendation.isBlank()) throw new IllegalStateException("Groq response missing sections");
        return new AiResult(limit(summary, 1500), limit(recommendation, 1500), SOURCE_GROQ, cfg.model());
    }

    /** Facts handed to the model. Only data the system actually measured. */
    private String describe(Incident i) {
        StringBuilder sb = new StringBuilder();
        sb.append("Incident ID: ").append(i.getIncidentId()).append('\n');
        sb.append("Status: ").append(i.statusEnum() == IncidentStatus.PENDING
                ? "PENDING REVIEW - potential fall; verification evidence was inconclusive"
                : "VERIFIED - fall confirmed by temporal verification").append('\n');
        sb.append("Resident: ").append(nz(i.getElderlyName(), "Resident"));
        if (i.getElderlyAge() != null) sb.append(", age ").append(i.getElderlyAge());
        sb.append('\n');
        sb.append("Camera: ").append(nz(i.getCameraName(), "Camera")).append('\n');
        if (i.getTimestamp() != null) {
            sb.append("Detected at: ").append(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss z").withZone(zone).format(i.getTimestamp())).append('\n');
        }
        sb.append("Detection confidence: ").append(Math.round(i.getConfidence() * 100)).append("%\n");
        DetectionDetails d = i.getDetection();
        if (d != null) {
            if (d.getPostureBefore() != null) sb.append("Posture before event: ").append(d.getPostureBefore()).append('\n');
            if (d.getPostureAfter() != null) sb.append("Posture after event: ").append(d.getPostureAfter()).append('\n');
            if (d.getObservedDownSeconds() != null) sb.append("Time observed on the ground: ").append(String.format(Locale.ROOT, "%.1f", d.getObservedDownSeconds())).append(" s\n");
            if (d.getVerificationSeconds() != null) sb.append("Verification window: ").append(String.format(Locale.ROOT, "%.0f", d.getVerificationSeconds())).append(" s\n");
            if (d.getMaxPeopleDetected() != null) sb.append("People visible in video: ").append(d.getMaxPeopleDetected()).append('\n');
            if (d.getReasons() != null && !d.getReasons().isEmpty()) sb.append("Detection evidence: ").append(String.join("; ", d.getReasons())).append('\n');
        }
        return sb.toString();
    }

    public AiResult fallback(Incident i) {
        String who = nz(i.getElderlyName(), "The resident");
        String where = nz(i.getCameraName(), "the monitored area");
        String when = i.getTimestamp() == null ? "" : " at " + DateTimeFormatter.ofPattern("HH:mm, d MMM yyyy").withZone(zone).format(i.getTimestamp());
        DetectionDetails d = i.getDetection();
        String down = d != null && d.getObservedDownSeconds() != null
                ? String.format(Locale.ROOT, " and remained on the ground for about %.0f seconds", d.getObservedDownSeconds()) : "";
        String summary;
        String recommendation;
        if (i.statusEnum() == IncidentStatus.PENDING) {
            summary = "A potential fall involving " + who + " was detected on " + where + when
                    + ". The available footage was not sufficient to confirm it automatically, so it is awaiting caregiver review. "
                    + "Detection confidence was " + Math.round(i.getConfidence() * 100) + "%.";
            recommendation = "Review the captured frame and check on " + who + " to confirm their wellbeing. "
                    + "Mark the incident as resolved or as a false alarm once it has been reviewed.";
        } else {
            summary = "A fall involving " + who + " was detected on " + where + when + down
                    + ". The event was confirmed by the system's verification check with " + Math.round(i.getConfidence() * 100) + "% confidence.";
            recommendation = "Check on " + who + " in person as soon as possible and offer assistance. "
                    + "If the resident is unresponsive, in pain, or unable to get up, contact emergency services. "
                    + "Record the outcome in the portal once the situation is resolved.";
        }
        return new AiResult(summary, recommendation, SOURCE_FALLBACK, null);
    }

    private static String nz(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }

    private static String limit(String v, int max) { return v.length() <= max ? v : v.substring(0, max); }

    private static class UnsupportedOptionsException extends Exception {}
}
