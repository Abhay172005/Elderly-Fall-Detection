package com.smartelderly.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Portal settings. The same shape is read and written; read-only fields are ignored on update. */
public record SettingsDto(
        @NotNull @DecimalMin(value = "0.30", message = "Minimum is 0.30") @DecimalMax(value = "0.99", message = "Maximum is 0.99") Double confidenceThreshold,
        @NotNull @Min(value = 2, message = "Minimum is 2 seconds") @Max(value = 30, message = "Maximum is 30 seconds") Integer verificationSeconds,
        @NotBlank(message = "Resident name is required") @Size(max = 80) String residentName,
        @Min(value = 40, message = "Age must be 40-130") @Max(value = 130, message = "Age must be 40-130") Integer residentAge,
        @NotBlank(message = "Camera name is required") @Size(max = 80) String cameraName,
        @Size(max = 120) String cameraLocation,
        @Email(message = "Enter a valid email address") @Size(max = 120) String emergencyEmail,
        boolean emailNotificationsEnabled,
        boolean attachPdfToEmail,
        boolean notifyOnPending,
        boolean recordInconclusive,
        Instant updatedAt,
        String updatedBy,
        Integrations integrations) {

    /** Read-only view of which external integrations are configured through environment variables. */
    public record Integrations(boolean groqConfigured, String groqModel, boolean smtpConfigured,
                               boolean fallbackEmergencyEmailConfigured) {}
}
