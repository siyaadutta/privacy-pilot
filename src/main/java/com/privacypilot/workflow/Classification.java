package com.privacypilot.workflow;

/**
 * Categories the response classifier may assign to an inbound company reply.
 *
 * <p>UNKNOWN is used when the classifier cannot confidently place the reply in
 * one of the concrete categories. Low-confidence or ambiguous replies are
 * routed to human review rather than being forced into a concrete category.
 */
public enum Classification {
    RECEIVED,
    COMPLETED,
    VERIFICATION_REQUIRED,
    MORE_INFORMATION_REQUIRED,
    REJECTED,
    UNKNOWN
}
