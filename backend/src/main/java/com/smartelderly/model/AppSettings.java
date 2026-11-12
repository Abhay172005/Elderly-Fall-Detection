package com.smartelderly.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Single portal-wide settings document (id = "global") in the {@code settings} collection. */
@Document("settings")
public class AppSettings {
    public static final String GLOBAL_ID = "global";

    @Id
    private String id = GLOBAL_ID;
    /** Minimum combined detection confidence for a VERIFIED incident; lower confirmed falls become PENDING. */
    private double confidenceThreshold;
    /** Seconds a person must stay down after a potential fall before it is confirmed. */
    private int verificationSeconds;
    private String residentName;
    private Integer residentAge;
    private String cameraName;
    private String cameraLocation;
    private String emergencyEmail;
    private boolean emailNotificationsEnabled = true;
    private boolean attachPdfToEmail = true;
    private boolean notifyOnPending = false;
    /** Store inconclusive potential falls as PENDING incidents for review. */
    private boolean recordInconclusive = true;
    @LastModifiedDate
    private Instant updatedAt;
    private String updatedBy;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
    public int getVerificationSeconds() { return verificationSeconds; }
    public void setVerificationSeconds(int verificationSeconds) { this.verificationSeconds = verificationSeconds; }
    public String getResidentName() { return residentName; }
    public void setResidentName(String residentName) { this.residentName = residentName; }
    public Integer getResidentAge() { return residentAge; }
    public void setResidentAge(Integer residentAge) { this.residentAge = residentAge; }
    public String getCameraName() { return cameraName; }
    public void setCameraName(String cameraName) { this.cameraName = cameraName; }
    public String getCameraLocation() { return cameraLocation; }
    public void setCameraLocation(String cameraLocation) { this.cameraLocation = cameraLocation; }
    public String getEmergencyEmail() { return emergencyEmail; }
    public void setEmergencyEmail(String emergencyEmail) { this.emergencyEmail = emergencyEmail; }
    public boolean isEmailNotificationsEnabled() { return emailNotificationsEnabled; }
    public void setEmailNotificationsEnabled(boolean emailNotificationsEnabled) { this.emailNotificationsEnabled = emailNotificationsEnabled; }
    public boolean isAttachPdfToEmail() { return attachPdfToEmail; }
    public void setAttachPdfToEmail(boolean attachPdfToEmail) { this.attachPdfToEmail = attachPdfToEmail; }
    public boolean isNotifyOnPending() { return notifyOnPending; }
    public void setNotifyOnPending(boolean notifyOnPending) { this.notifyOnPending = notifyOnPending; }
    public boolean isRecordInconclusive() { return recordInconclusive; }
    public void setRecordInconclusive(boolean recordInconclusive) { this.recordInconclusive = recordInconclusive; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
