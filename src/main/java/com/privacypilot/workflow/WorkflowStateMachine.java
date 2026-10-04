package com.privacypilot.workflow;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The deletion-request workflow, modeled as an explicit directed graph of legal
 * transitions rather than a free-form status string.
 *
 * <p>Legal transitions (spec):
 * <pre>
 *   DRAFT      -> APPROVED
 *   APPROVED   -> SENT
 *   SENT       -> ACKNOWLEDGED | WAITING
 *   ACKNOWLEDGED -> WAITING
 *   WAITING    -> COMPLETED
 *              -> VERIFICATION_REQUIRED
 *              -> MORE_INFORMATION_REQUIRED
 *              -> FOLLOW_UP_DUE
 *              -> REJECTED
 *              -> USER_ACTION_REQUIRED   (genuine AI uncertainty; not a proxy)
 *   VERIFICATION_REQUIRED     -> USER_ACTION_REQUIRED
 *   MORE_INFORMATION_REQUIRED -> USER_ACTION_REQUIRED
 *   FOLLOW_UP_DUE  -> FOLLOW_UP_SENT
 *   FOLLOW_UP_SENT -> WAITING
 *   USER_ACTION_REQUIRED -> WAITING | COMPLETED | REJECTED
 * </pre>
 *
 * <p>Any transition not listed here is rejected by {@link #validateTransition}.
 * COMPLETED and REJECTED are terminal.
 */
@Component
public class WorkflowStateMachine {

    private final Map<RequestStatus, Set<RequestStatus>> allowed = new EnumMap<>(RequestStatus.class);

    public WorkflowStateMachine() {
        allowed.put(RequestStatus.DRAFT, EnumSet.of(RequestStatus.APPROVED));
        allowed.put(RequestStatus.APPROVED, EnumSet.of(RequestStatus.SENT));
        allowed.put(RequestStatus.SENT, EnumSet.of(RequestStatus.ACKNOWLEDGED, RequestStatus.WAITING));
        allowed.put(RequestStatus.ACKNOWLEDGED, EnumSet.of(RequestStatus.WAITING));
        allowed.put(RequestStatus.WAITING, EnumSet.of(
                RequestStatus.COMPLETED,
                RequestStatus.VERIFICATION_REQUIRED,
                RequestStatus.MORE_INFORMATION_REQUIRED,
                RequestStatus.FOLLOW_UP_DUE,
                RequestStatus.REJECTED,
                // Genuine AI uncertainty (low confidence / ambiguous reply) routes
                // directly to human review without being mislabeled as an
                // identity-verification request.
                RequestStatus.USER_ACTION_REQUIRED));
        allowed.put(RequestStatus.VERIFICATION_REQUIRED, EnumSet.of(RequestStatus.USER_ACTION_REQUIRED));
        allowed.put(RequestStatus.MORE_INFORMATION_REQUIRED, EnumSet.of(RequestStatus.USER_ACTION_REQUIRED));
        allowed.put(RequestStatus.FOLLOW_UP_DUE, EnumSet.of(RequestStatus.FOLLOW_UP_SENT));
        allowed.put(RequestStatus.FOLLOW_UP_SENT, EnumSet.of(RequestStatus.WAITING));
        allowed.put(RequestStatus.USER_ACTION_REQUIRED, EnumSet.of(
                RequestStatus.WAITING,
                RequestStatus.COMPLETED,
                RequestStatus.REJECTED));
        // Terminal states — no outgoing edges.
        allowed.put(RequestStatus.COMPLETED, EnumSet.noneOf(RequestStatus.class));
        allowed.put(RequestStatus.REJECTED, EnumSet.noneOf(RequestStatus.class));
    }

    /**
     * @return true if {@code from -> to} is a legal transition.
     */
    public boolean canTransition(RequestStatus from, RequestStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return allowed.getOrDefault(from, EnumSet.noneOf(RequestStatus.class)).contains(to);
    }

    /**
     * @throws InvalidTransitionException if {@code from -> to} is illegal.
     */
    public void validateTransition(RequestStatus from, RequestStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidTransitionException(from, to);
        }
    }

    /**
     * @return the set of states reachable in one step from {@code from}.
     */
    public Set<RequestStatus> nextStates(RequestStatus from) {
        Set<RequestStatus> next = allowed.getOrDefault(from, EnumSet.noneOf(RequestStatus.class));
        return EnumSet.copyOf(next.isEmpty() ? EnumSet.noneOf(RequestStatus.class) : next);
    }

    public boolean isTerminal(RequestStatus status) {
        return status == RequestStatus.COMPLETED || status == RequestStatus.REJECTED;
    }
}
