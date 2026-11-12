package com.smartelderly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Typed view of every {@code app.*} property. Values come from environment variables or the project .env file. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String storageDir,
        String timezone,
        Cors cors,
        Security security,
        Ml ml,
        Groq groq,
        Mail mail,
        Detection detection) {

    public record Cors(List<String> allowedOrigins) {}

    public record Security(String jwtSecret, int jwtExpirationHours, boolean allowRegistration,
                           String bootstrapAdminEmail, String bootstrapAdminPassword, String bootstrapAdminName) {}

    public record Ml(String url, String serviceKey, int timeoutSeconds) {}

    public record Groq(String key, String model, String url, int timeoutSeconds) {
        public boolean configured() {
            return key != null && !key.isBlank() && model != null && !model.isBlank();
        }
    }

    public record Mail(String from, String emergencyEmail) {}

    public record Detection(double defaultConfidenceThreshold, int defaultVerificationSeconds) {}
}
