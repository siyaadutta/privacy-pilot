package com.privacypilot.workflow;

/**
 * Thrown when a caller attempts an illegal status transition.
 */
public class InvalidTransitionException extends RuntimeException {

    private final RequestStatus from;
    private final RequestStatus to;

    public InvalidTransitionException(RequestStatus from, RequestStatus to) {
        super("Illegal workflow transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public RequestStatus getFrom() {
        return from;
    }

    public RequestStatus getTo() {
        return to;
    }
}
