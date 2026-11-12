package com.smartelderly.model;

/**
 * Lifecycle of an incident.
 * VERIFIED    - fall confirmed by temporal verification in the ML service.
 * PENDING     - potential fall whose evidence was inconclusive; needs caregiver review.
 * RESOLVED    - a caregiver responded and closed the incident.
 * FALSE_ALARM - a caregiver determined that no fall occurred.
 */
public enum IncidentStatus {
    VERIFIED, PENDING, RESOLVED, FALSE_ALARM;

    public boolean isOpen() {
        return this == VERIFIED || this == PENDING;
    }

    /** Tolerates legacy/unknown values stored by earlier versions. */
    public static IncidentStatus parse(String value) {
        if (value == null || value.isBlank()) return PENDING;
        try {
            return valueOf(value.trim().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
