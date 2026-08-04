package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;

/** ThreadCommand identity, derived-state and durable marker invariants. */
class ThreadCommandTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant CANCELLED = CREATED.plusSeconds(1);

  @Test
  void derivesStateWithoutPersistingStatusOrAppliedAt() {
    ThreadCommand queued = command(1L, 1L, 1L, null, null);
    ThreadCommand applied = command(2L, 1L, 2L, 99L, null);
    ThreadCommand cancelled = command(3L, 1L, 3L, null, CANCELLED);

    assertEquals(ThreadCommandState.QUEUED, queued.state());
    assertEquals(ThreadCommandState.APPLIED, applied.state());
    assertEquals(ThreadCommandState.CANCELLED, cancelled.state());
    assertEquals(ThreadCommandType.SET_AGENT, queued.type());
    assertFalse(queued.state().isTerminal());
    assertTrue(applied.state().isTerminal());
    assertTrue(cancelled.state().isTerminal());
    assertEquals(
        List.of(
            "id",
            "threadId",
            "sequence",
            "payload",
            "clientCommandId",
            "consumedTurnStartEntryId",
            "cancelledAt",
            "createdAt"),
        List.of(ThreadCommand.class.getRecordComponents()).stream()
            .map(RecordComponent::getName)
            .toList());
  }

  @Test
  void rejectsInvalidIdentitySequenceAndTerminalMarkers() {
    assertThrows(IllegalArgumentException.class, () -> command(0L, 1L, 1L, null, null));
    assertThrows(IllegalArgumentException.class, () -> command(1L, 0L, 1L, null, null));
    assertThrows(IllegalArgumentException.class, () -> command(1L, 1L, 0L, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommand(1L, 1L, 1L, payload(), " client", null, null, CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommand(1L, 1L, 1L, payload(), " ", null, null, CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommand(1L, 1L, 1L, payload(), "c".repeat(129), null, null, CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommand(1L, 1L, 1L, payload(), "client", 0L, null, CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommand(1L, 1L, 1L, payload(), "client", 99L, CANCELLED, CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadCommand(
                1L, 1L, 1L, payload(), "client", null, CREATED.minusSeconds(1), CREATED));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadCommand(1L, 1L, 1L, payload(), "client", null, null, null));
  }

  @Test
  void consumeTransitionsQueuedToAppliedWithConsumedTurnStart() {
    ThreadCommand queued = command(1L, 1L, 1L, null, null);
    ThreadCommand consumed = queued.consume(99L);
    assertEquals(ThreadCommandState.APPLIED, consumed.state());
    assertEquals(99L, consumed.consumedTurnStartEntryId());
    assertNull(consumed.cancelledAt());
    // identity 不变
    assertEquals(queued.id(), consumed.id());
    assertEquals(queued.threadId(), consumed.threadId());
    assertEquals(queued.sequence(), consumed.sequence());
    assertEquals(queued.payload(), consumed.payload());
    assertEquals(queued.clientCommandId(), consumed.clientCommandId());
    assertEquals(queued.createdAt(), consumed.createdAt());
  }

  @Test
  void cancelTransitionsQueuedToCancelledWithCancelledAt() {
    ThreadCommand queued = command(1L, 1L, 1L, null, null);
    ThreadCommand cancelled = queued.cancel(CANCELLED);
    assertEquals(ThreadCommandState.CANCELLED, cancelled.state());
    assertEquals(CANCELLED, cancelled.cancelledAt());
    assertNull(cancelled.consumedTurnStartEntryId());
  }

  @Test
  void terminalCommandsRejectFurtherTransitions() {
    ThreadCommand applied = command(1L, 1L, 1L, 99L, null);
    ThreadCommand cancelled = command(2L, 1L, 2L, null, CANCELLED);
    assertThrows(IllegalStateException.class, () -> applied.consume(100L));
    assertThrows(IllegalStateException.class, () -> applied.cancel(CANCELLED));
    assertThrows(IllegalStateException.class, () -> cancelled.consume(100L));
    assertThrows(IllegalStateException.class, () -> cancelled.cancel(CANCELLED.plusSeconds(1)));
  }

  @Test
  void consumeRejectsNonPositiveTurnStartAndCancelRejectsPastTime() {
    ThreadCommand queued = command(1L, 1L, 1L, null, null);
    assertThrows(IllegalArgumentException.class, () -> queued.consume(0L));
    assertThrows(IllegalArgumentException.class, () -> queued.consume(-1L));
    assertThrows(IllegalArgumentException.class, () -> queued.cancel(CREATED.minusSeconds(1)));
    assertThrows(NullPointerException.class, () -> queued.cancel(null));
  }

  private static ThreadCommand command(
      long id, long threadId, long sequence, Long consumedTurnStartEntryId, Instant cancelledAt) {
    return new ThreadCommand(
        id,
        threadId,
        sequence,
        payload(),
        "client-" + id,
        consumedTurnStartEntryId,
        cancelledAt,
        CREATED);
  }

  private static ThreadCommandPayload payload() {
    return new SetAgentCommandPayload("coding");
  }
}
