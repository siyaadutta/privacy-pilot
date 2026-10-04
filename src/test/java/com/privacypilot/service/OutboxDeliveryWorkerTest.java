package com.privacypilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.privacypilot.domain.OutboxEmail;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level coverage of the worker's orchestration. The DB-level claim/fence
 * semantics (SKIP LOCKED, lease recovery, token fencing) are proven separately in
 * the Testcontainers integration tests, since they require a real PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class OutboxDeliveryWorkerTest {

    @Mock private OutboxStore store;
    @Mock private EmailSender emailSender;

    private OutboxEmail row(String key, int attempts, String token) {
        OutboxEmail e = new OutboxEmail(key, 1L, "from@x", "to@y", "subj", "body");
        e.setAttempts(attempts);
        e.setClaimToken(token);
        return e;
    }

    private OutboxDeliveryWorker worker() {
        return new OutboxDeliveryWorker(store, emailSender, 120);
    }

    @Test
    void successfulDeliveryMarksSentViaStoreWithToken() {
        OutboxEmail e = row("k1", 1, "tok-1");
        e.setId(10L);
        when(store.claim(anyString(), anyInt(), any(Duration.class))).thenReturn(List.of(e));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(store.markSent(10L, "tok-1")).thenReturn(true);

        int sent = worker().drainOnce();

        assertThat(sent).isEqualTo(1);
        verify(store).markSent(10L, "tok-1");
        verify(store, never()).markFailure(anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void failedDeliveryCallsMarkFailureWithToken() {
        OutboxEmail e = row("k2", 2, "tok-2");
        e.setId(11L);
        when(store.claim(anyString(), anyInt(), any(Duration.class))).thenReturn(List.of(e));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        when(store.markFailure(eq(11L), eq("tok-2"), eq(5), anyString())).thenReturn(true);

        int sent = worker().drainOnce();

        assertThat(sent).isZero();
        verify(store).markFailure(eq(11L), eq("tok-2"), eq(5), anyString());
        verify(store, never()).markSent(anyLong(), anyString());
    }

    @Test
    void lostClaimOnMarkSentIsNotCountedAsSent() {
        // Send succeeded but the claim was lost (lease expired, another worker won):
        // markSent returns false -> not counted, no exception.
        OutboxEmail e = row("k5", 1, "stale");
        e.setId(15L);
        when(store.claim(anyString(), anyInt(), any(Duration.class))).thenReturn(List.of(e));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(store.markSent(15L, "stale")).thenReturn(false);

        int sent = worker().drainOnce();

        assertThat(sent).isZero();
        verify(store).markSent(15L, "stale");
    }

    @Test
    void sendExceptionIsTreatedAsFailureNotBatchAbort() {
        OutboxEmail a = row("k3", 1, "ta"); a.setId(1L);
        OutboxEmail b = row("k4", 1, "tb"); b.setId(2L);
        when(store.claim(anyString(), anyInt(), any(Duration.class))).thenReturn(List.of(a, b));
        when(emailSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("smtp blew up"))
                .thenReturn(true);
        when(store.markFailure(eq(1L), eq("ta"), anyInt(), anyString())).thenReturn(true);
        when(store.markSent(2L, "tb")).thenReturn(true);

        int sent = worker().drainOnce();

        // First row failed via exception, second still processed.
        assertThat(sent).isEqualTo(1);
        verify(store).markFailure(eq(1L), eq("ta"), anyInt(), anyString());
        verify(store).markSent(2L, "tb");
    }

    @Test
    void emptyClaimDoesNothing() {
        when(store.claim(anyString(), anyInt(), any(Duration.class))).thenReturn(List.of());
        int sent = worker().drainOnce();
        assertThat(sent).isZero();
        verify(emailSender, never()).send(anyString(), anyString(), anyString(), anyString());
    }
}
