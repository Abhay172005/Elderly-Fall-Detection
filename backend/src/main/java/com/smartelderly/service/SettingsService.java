package com.smartelderly.service;

import com.smartelderly.config.AppProperties;
import com.smartelderly.dto.SettingsDto;
import com.smartelderly.model.AppSettings;
import com.smartelderly.repository.SettingsRepository;
import org.springframework.stereotype.Service;

/** Reads and persists the global settings document, seeding defaults from environment variables. */
@Service
public class SettingsService {
    private final SettingsRepository repo;
    private final AppProperties props;
    private final EmailService email;

    public SettingsService(SettingsRepository repo, AppProperties props, EmailService email) {
        this.repo = repo;
        this.props = props;
        this.email = email;
    }

    public AppSettings current() {
        return repo.findById(AppSettings.GLOBAL_ID).orElseGet(this::defaults);
    }

    public SettingsDto get() {
        return toDto(current());
    }

    public SettingsDto update(SettingsDto dto, String updatedBy) {
        AppSettings s = current();
        s.setConfidenceThreshold(Math.round(dto.confidenceThreshold() * 100) / 100.0);
        s.setVerificationSeconds(dto.verificationSeconds());
        s.setResidentName(dto.residentName().trim());
        s.setResidentAge(dto.residentAge());
        s.setCameraName(dto.cameraName().trim());
        s.setCameraLocation(blankToNull(dto.cameraLocation()));
        s.setEmergencyEmail(blankToNull(dto.emergencyEmail()));
        s.setEmailNotificationsEnabled(dto.emailNotificationsEnabled());
        s.setAttachPdfToEmail(dto.attachPdfToEmail());
        s.setNotifyOnPending(dto.notifyOnPending());
        s.setRecordInconclusive(dto.recordInconclusive());
        s.setUpdatedBy(updatedBy);
        return toDto(repo.save(s));
    }

    /** Recipient for alerts: the address saved in settings, otherwise EMERGENCY_EMAIL from the environment. */
    public String emergencyRecipient(AppSettings s) {
        if (s.getEmergencyEmail() != null && !s.getEmergencyEmail().isBlank()) return s.getEmergencyEmail();
        String fallback = props.mail().emergencyEmail();
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    private AppSettings defaults() {
        AppSettings s = new AppSettings();
        double threshold = props.detection().defaultConfidenceThreshold();
        s.setConfidenceThreshold(threshold >= 0.3 && threshold <= 0.99 ? threshold : 0.70);
        int seconds = props.detection().defaultVerificationSeconds();
        s.setVerificationSeconds(seconds >= 2 && seconds <= 30 ? seconds : 5);
        s.setResidentName("Resident");
        s.setResidentAge(null);
        s.setCameraName("Living Room Camera");
        return s;
    }

    private SettingsDto toDto(AppSettings s) {
        String fallback = props.mail().emergencyEmail();
        var integrations = new SettingsDto.Integrations(props.groq().configured(), props.groq().model(),
                email.smtpConfigured(), fallback != null && !fallback.isBlank());
        return new SettingsDto(s.getConfidenceThreshold(), s.getVerificationSeconds(), s.getResidentName(), s.getResidentAge(),
                s.getCameraName(), s.getCameraLocation(), s.getEmergencyEmail(), s.isEmailNotificationsEnabled(),
                s.isAttachPdfToEmail(), s.isNotifyOnPending(), s.isRecordInconclusive(), s.getUpdatedAt(), s.getUpdatedBy(),
                integrations);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
