package com.smartelderly.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartelderly.config.AppProperties;
import com.smartelderly.dto.HealthDto;
import com.smartelderly.dto.HealthDto.Component;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Probes MongoDB and the ML service and reports which optional integrations are configured. */
@Service
public class HealthService {
    private final MongoTemplate mongo;
    private final MlServiceClient ml;
    private final AppProperties props;
    private final EmailService email;

    public HealthService(MongoTemplate mongo, MlServiceClient ml, AppProperties props, EmailService email) {
        this.mongo = mongo;
        this.ml = ml;
        this.props = props;
        this.email = email;
    }

    public HealthDto check() {
        CompletableFuture<Component> db = CompletableFuture.supplyAsync(this::database);
        CompletableFuture<Component> mlService = CompletableFuture.supplyAsync(this::mlService);
        Component dbResult = await(db, "Database check timed out.");
        Component mlResult = await(mlService, "ML service check timed out.");

        Component ai = props.groq().configured()
                ? new Component("CONFIGURED", "Groq · " + props.groq().model(), null, null)
                : Component.of("NOT_CONFIGURED", "GROQ_API_KEY not set; fallback summaries are used.");
        Component mail = email.smtpConfigured()
                ? Component.of("CONFIGURED", "SMTP credentials present.")
                : Component.of("NOT_CONFIGURED", "MAIL_USERNAME / MAIL_PASSWORD not set; emails are not sent.");

        String overall = "UP".equals(dbResult.status()) && "UP".equals(mlResult.status()) ? "UP"
                : "UP".equals(dbResult.status()) ? "DEGRADED" : "DOWN";
        return new HealthDto(overall, Instant.now(), Component.of("UP", "Spring Boot API is running."), dbResult, mlResult, ai, mail);
    }

    private Component database() {
        long start = System.nanoTime();
        try {
            mongo.getDb().runCommand(new Document("ping", 1));
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new Component("UP", "Connected to " + mongo.getDb().getName(), ms, null);
        } catch (Exception e) {
            return Component.of("DOWN", "MongoDB unreachable (" + e.getClass().getSimpleName() + ")");
        }
    }

    private Component mlService() {
        long start = System.nanoTime();
        try {
            JsonNode h = ml.health();
            long ms = (System.nanoTime() - start) / 1_000_000;
            Map<String, Object> info = new LinkedHashMap<>();
            if (h != null) {
                info.put("modelLoaded", h.path("modelLoaded").asBoolean(false));
                info.put("model", h.path("model").asText(null));
                info.put("activeJobs", h.path("activeJobs").asInt(0));
                info.put("device", h.path("device").asText(null));
            }
            boolean loaded = h != null && h.path("modelLoaded").asBoolean(false);
            return new Component(loaded ? "UP" : "DEGRADED", loaded ? "YOLO Pose model loaded." : "ML service up, model not loaded.", ms, info);
        } catch (Exception e) {
            return Component.of("DOWN", "ML service is not reachable.");
        }
    }

    private static Component await(CompletableFuture<Component> f, String timeoutMessage) {
        try {
            return f.get(6, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Component.of("DOWN", timeoutMessage);
        }
    }
}
