package com.privacypilot.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class WorkflowStateMachineTest {

    private final WorkflowStateMachine sm = new WorkflowStateMachine();

    @Test
    void happyPathTransitionsAreLegal() {
        assertThat(sm.canTransition(RequestStatus.DRAFT, RequestStatus.APPROVED)).isTrue();
        assertThat(sm.canTransition(RequestStatus.APPROVED, RequestStatus.SENT)).isTrue();
        assertThat(sm.canTransition(RequestStatus.SENT, RequestStatus.WAITING)).isTrue();
        assertThat(sm.canTransition(RequestStatus.WAITING, RequestStatus.COMPLETED)).isTrue();
    }

    @Test
    void branchTransitionsAreLegal() {
        assertThat(sm.canTransition(RequestStatus.WAITING, RequestStatus.VERIFICATION_REQUIRED)).isTrue();
        assertThat(sm.canTransition(RequestStatus.VERIFICATION_REQUIRED, RequestStatus.USER_ACTION_REQUIRED)).isTrue();
        assertThat(sm.canTransition(RequestStatus.WAITING, RequestStatus.FOLLOW_UP_DUE)).isTrue();
        assertThat(sm.canTransition(RequestStatus.FOLLOW_UP_DUE, RequestStatus.FOLLOW_UP_SENT)).isTrue();
        assertThat(sm.canTransition(RequestStatus.FOLLOW_UP_SENT, RequestStatus.WAITING)).isTrue();
        assertThat(sm.canTransition(RequestStatus.WAITING, RequestStatus.REJECTED)).isTrue();
        // Genuine AI uncertainty: direct edge, not via VERIFICATION_REQUIRED.
        assertThat(sm.canTransition(RequestStatus.WAITING, RequestStatus.USER_ACTION_REQUIRED)).isTrue();
    }

    @Test
    void illegalJumpsAreRejected() {
        // Skipping states
        assertThat(sm.canTransition(RequestStatus.DRAFT, RequestStatus.SENT)).isFalse();
        assertThat(sm.canTransition(RequestStatus.DRAFT, RequestStatus.COMPLETED)).isFalse();
        // Going backwards
        assertThat(sm.canTransition(RequestStatus.SENT, RequestStatus.DRAFT)).isFalse();
        // Out of a terminal state
        assertThat(sm.canTransition(RequestStatus.COMPLETED, RequestStatus.WAITING)).isFalse();
        assertThat(sm.canTransition(RequestStatus.REJECTED, RequestStatus.WAITING)).isFalse();
    }

    @Test
    void validateTransitionThrowsOnIllegalJump() {
        assertThatThrownBy(() -> sm.validateTransition(RequestStatus.DRAFT, RequestStatus.COMPLETED))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("DRAFT")
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void validateTransitionPassesOnLegalTransition() {
        assertDoesNotThrow(() -> sm.validateTransition(RequestStatus.DRAFT, RequestStatus.APPROVED));
    }

    @Test
    void nullsAreNotTransitionable() {
        assertThat(sm.canTransition(null, RequestStatus.APPROVED)).isFalse();
        assertThat(sm.canTransition(RequestStatus.DRAFT, null)).isFalse();
    }

    @Test
    void terminalStatesAreTerminal() {
        assertThat(sm.isTerminal(RequestStatus.COMPLETED)).isTrue();
        assertThat(sm.isTerminal(RequestStatus.REJECTED)).isTrue();
        assertThat(sm.isTerminal(RequestStatus.WAITING)).isFalse();
        assertThat(sm.nextStates(RequestStatus.COMPLETED)).isEmpty();
    }
}
