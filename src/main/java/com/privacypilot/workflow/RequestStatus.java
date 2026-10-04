package com.privacypilot.workflow;

/**
 * States a deletion request can be in.
 *
 * <p>Transitions between these states are governed by {@link WorkflowStateMachine};
 * this enum only enumerates the legal states, not the legal edges.
 */
public enum RequestStatus {
    DRAFT,
    APPROVED,
    SENT,
    ACKNOWLEDGED,
    WAITING,
    VERIFICATION_REQUIRED,
    MORE_INFORMATION_REQUIRED,
    FOLLOW_UP_DUE,
    FOLLOW_UP_SENT,
    USER_ACTION_REQUIRED,
    REJECTED,
    COMPLETED
}
