package com.smartelderly.model;

import java.util.List;

/**
 * Explainable evidence produced by the ML service for one detection. Embedded inside an incident document.
 */
public class DetectionDetails {
    private String outcome;
    private String verificationStatus;
    private Double verificationSeconds;
    private Double observedDownSeconds;
    private Double fallStartSeconds;
    private Double confirmedAtSeconds;
    private Double fallScore;
    private Double downRatio;
    private Double poseQuality;
    private String postureBefore;
    private String postureAfter;
    private Integer framesAnalyzed;
    private Integer maxPeopleDetected;
    private Integer trackId;
    private String videoName;
    private Double videoDurationSeconds;
    private Long processingMs;
    private String modelName;
    private Double confidenceThreshold;
    private List<String> reasons;

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }
    public Double getVerificationSeconds() { return verificationSeconds; }
    public void setVerificationSeconds(Double verificationSeconds) { this.verificationSeconds = verificationSeconds; }
    public Double getObservedDownSeconds() { return observedDownSeconds; }
    public void setObservedDownSeconds(Double observedDownSeconds) { this.observedDownSeconds = observedDownSeconds; }
    public Double getFallStartSeconds() { return fallStartSeconds; }
    public void setFallStartSeconds(Double fallStartSeconds) { this.fallStartSeconds = fallStartSeconds; }
    public Double getConfirmedAtSeconds() { return confirmedAtSeconds; }
    public void setConfirmedAtSeconds(Double confirmedAtSeconds) { this.confirmedAtSeconds = confirmedAtSeconds; }
    public Double getFallScore() { return fallScore; }
    public void setFallScore(Double fallScore) { this.fallScore = fallScore; }
    public Double getDownRatio() { return downRatio; }
    public void setDownRatio(Double downRatio) { this.downRatio = downRatio; }
    public Double getPoseQuality() { return poseQuality; }
    public void setPoseQuality(Double poseQuality) { this.poseQuality = poseQuality; }
    public String getPostureBefore() { return postureBefore; }
    public void setPostureBefore(String postureBefore) { this.postureBefore = postureBefore; }
    public String getPostureAfter() { return postureAfter; }
    public void setPostureAfter(String postureAfter) { this.postureAfter = postureAfter; }
    public Integer getFramesAnalyzed() { return framesAnalyzed; }
    public void setFramesAnalyzed(Integer framesAnalyzed) { this.framesAnalyzed = framesAnalyzed; }
    public Integer getMaxPeopleDetected() { return maxPeopleDetected; }
    public void setMaxPeopleDetected(Integer maxPeopleDetected) { this.maxPeopleDetected = maxPeopleDetected; }
    public Integer getTrackId() { return trackId; }
    public void setTrackId(Integer trackId) { this.trackId = trackId; }
    public String getVideoName() { return videoName; }
    public void setVideoName(String videoName) { this.videoName = videoName; }
    public Double getVideoDurationSeconds() { return videoDurationSeconds; }
    public void setVideoDurationSeconds(Double videoDurationSeconds) { this.videoDurationSeconds = videoDurationSeconds; }
    public Long getProcessingMs() { return processingMs; }
    public void setProcessingMs(Long processingMs) { this.processingMs = processingMs; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(Double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
    public List<String> getReasons() { return reasons; }
    public void setReasons(List<String> reasons) { this.reasons = reasons; }
}
