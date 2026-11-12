package com.smartelderly.model;

/**
 * Outcome of the e-mail notification attempt. SENT is only recorded after the SMTP server accepted the message.
 */
public enum EmailStatus {
    /** Not attempted yet. */
    PENDING,
    SENT,
    FAILED,
    /** SMTP credentials or recipient missing. */
    NOT_CONFIGURED,
    /** Notifications switched off in settings. */
    DISABLED,
    /** Not sent by policy, e.g. inconclusive incidents. */
    SKIPPED;

    public static EmailStatus parse(String value) {
        if (value == null || value.isBlank()) return PENDING;
        try {
            return valueOf(value.trim().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
