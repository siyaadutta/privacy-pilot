package com.privacypilot.domain;

/**
 * Direction of an email message relative to PrivacyPilot.
 */
public enum EmailDirection {
    /** Sent by PrivacyPilot to the company (request or follow-up). */
    OUTBOUND,
    /** Received from the company. */
    INBOUND
}
