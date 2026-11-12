package com.smartelderly.model;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One fall incident in {@code smart_elderly_portal.incidents}.
 * Field names of the original prototype (elderlyName, imagePath, pdfPath, ...) are kept so existing documents still map.
 * Status values are stored as strings and interpreted through {@link IncidentStatus} / {@link EmailStatus}
 * so legacy values never break deserialisation. Indexes are created by {@code MongoIndexInitializer}.
 */
@Document("incidents")
public class Incident {
    @Id
    private String id;
    private String incidentId;
    /** ML job id; unique so a retried callback can never create a duplicate incident. */
    private String detectionId;
    private String source;

    private String elderlyName;
    private Integer elderlyAge;
    private String cameraName;
    private Instant timestamp;
    private double confidence;
    private String status;

    private String imagePath;
    private DetectionDetails detection;

    private String aiSummary;
    private String caregiverRecommendation;
    private String aiSource;
    private String aiModel;

    private String pdfPath;
    private Instant pdfGeneratedAt;

    private String emailStatus;
    private String emailDetail;
    private Instant emailAttemptedAt;

    private Instant acknowledgedAt;
    private String acknowledgedBy;
    private String acknowledgementNote;
    private Instant resolvedAt;
    private String resolvedBy;
    private String resolutionNote;

    @CreatedDate
    private Instant createdAt;
    @LastModifiedDate
    private Instant updatedAt;

    public IncidentStatus statusEnum() { return IncidentStatus.parse(status); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getDetectionId() { return detectionId; }
    public void setDetectionId(String detectionId) { this.detectionId = detectionId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getElderlyName() { return elderlyName; }
    public void setElderlyName(String elderlyName) { this.elderlyName = elderlyName; }
    public Integer getElderlyAge() { return elderlyAge; }
    public void setElderlyAge(Integer elderlyAge) { this.elderlyAge = elderlyAge; }
    public String getCameraName() { return cameraName; }
    public void setCameraName(String cameraName) { this.cameraName = cameraName; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public DetectionDetails getDetection() { return detection; }
    public void setDetection(DetectionDetails detection) { this.detection = detection; }
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
    public String getCaregiverRecommendation() { return caregiverRecommendation; }
    public void setCaregiverRecommendation(String caregiverRecommendation) { this.caregiverRecommendation = caregiverRecommendation; }
    public String getAiSource() { return aiSource; }
    public void setAiSource(String aiSource) { this.aiSource = aiSource; }
    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }
    public Instant getPdfGeneratedAt() { return pdfGeneratedAt; }
    public void setPdfGeneratedAt(Instant pdfGeneratedAt) { this.pdfGeneratedAt = pdfGeneratedAt; }
    public String getEmailStatus() { return emailStatus; }
    public void setEmailStatus(String emailStatus) { this.emailStatus = emailStatus; }
    public String getEmailDetail() { return emailDetail; }
    public void setEmailDetail(String emailDetail) { this.emailDetail = emailDetail; }
    public Instant getEmailAttemptedAt() { return emailAttemptedAt; }
    public void setEmailAttemptedAt(Instant emailAttemptedAt) { this.emailAttemptedAt = emailAttemptedAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
    public String getAcknowledgementNote() { return acknowledgementNote; }
    public void setAcknowledgementNote(String acknowledgementNote) { this.acknowledgementNote = acknowledgementNote; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
