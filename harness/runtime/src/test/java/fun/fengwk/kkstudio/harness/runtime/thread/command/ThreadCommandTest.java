package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ThreadCommand identity、derived-state 与 durable marker 不变量。 */
class ThreadCommandTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant CANCELLED = CREATED.plusSeconds(1);
  private static final UUID STOP_REQUEST = id(88L);

  @Test
  void derivesStateWithoutPersistingStatusOrAppliedAt() {
    ThreadCommand queued = command(1L, 1L, 1L, null, null, null);
    ThreadCommand applied = command(2L, 1L, 2L, id(99), null, null);
    ThreadCommand cancelled = command(3L, 1L, 3L, null, STOP_REQUEST, CANCELLED);

    assertEquals(ThreadCommandState.QUEUED, queued.state());
    assertEquals(ThreadCommandState.APPLIED, applied.state());
    assertEquals(ThreadCommandState.CANCELLED, cancelled.state());
    assertEquals(ThreadCommandType.SET_AGENT, queued.type());
    assertFalse(queued.state().isTerminal());
    assertTrue(applied.state().isTerminal());
    assertTrue(cancelled.state().isTerminal());
    assertEquals(
        List.of(
            "threadId",
            "sequence",
            "payload",
            "clientCommandId",
            "requestHash",
            "consumedTurnStartEntryId",
            "cancelRequestId",
            "cancelledAt",
            "createdAt"),
        List.of(ThreadCommand.class.getRecordComponents()).stream()
            .map(RecordComponent::getName)
            .toList());
  }

  @Test
  void rejectsInvalidIdentitySequenceAndTerminalMarkers() {
    assertThrows(IllegalArgumentException.class, () -> command(1L, 1L, 0L, null, null, null));
    assertThrows(IllegalArgumentException.class, () -> command(1L, 1L, -1L, null, null, null));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadCommand(id(1L), 1L, payload(), null, HASH, null, null, null, CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ThreadCommand(id(1L), 0L, payload(), id(99L), HASH, null, null, null, CREATED));
    // consumed 与 cancelRequestId 互斥。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadCommand(
                id(1L), 1L, payload(), id(99L), HASH, id(99L), STOP_REQUEST, CANCELLED, CREATED));
    // cancelledAt 不能早于 createdAt。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadCommand(
                id(1L),
                1L,
                payload(),
                id(99L),
                HASH,
                null,
                STOP_REQUEST,
                CREATED.minusSeconds(1),
                CREATED));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadCommand(id(1L), 1L, payload(), id(99L), HASH, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadCommand(
                id(1L), 1L, payload(), id(99L), "not-a-hash", null, null, null, CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadCommand(
                id(1L), 1L, payload(), id(99L), "A".repeat(64), null, null, null, CREATED));
  }

  @Test
  void consumeTransitionsQueuedToAppliedWithConsumedTurnStart() {
    ThreadCommand queued = command(1L, 1L, 1L, null, null, null);
    ThreadCommand consumed = queued.consume(id(99));
    assertEquals(ThreadCommandState.APPLIED, consumed.state());
    assertEquals(id(99), consumed.consumedTurnStartEntryId());
    assertNull(consumed.cancelRequestId());
    assertNull(consumed.cancelledAt());
    // identity 不变
    assertEquals(queued.threadId(), consumed.threadId());
    assertEquals(queued.sequence(), consumed.sequence());
    assertEquals(queued.payload(), consumed.payload());
    assertEquals(queued.clientCommandId(), consumed.clientCommandId());
    assertEquals(queued.requestHash(), consumed.requestHash());
    assertEquals(queued.createdAt(), consumed.createdAt());
  }

  @Test
  void cancelTransitionsQueuedToCancelledWithCancelReceipt() {
    ThreadCommand queued = command(1L, 1L, 1L, null, null, null);
    ThreadCommand cancelled = queued.cancel(STOP_REQUEST, CANCELLED);
    assertEquals(ThreadCommandState.CANCELLED, cancelled.state());
    assertEquals(STOP_REQUEST, cancelled.cancelRequestId());
    assertEquals(CANCELLED, cancelled.cancelledAt());
    assertNull(cancelled.consumedTurnStartEntryId());
  }

  @Test
  void terminalCommandsRejectFurtherTransitions() {
    ThreadCommand applied = command(1L, 1L, 1L, id(99), null, null);
    ThreadCommand cancelled = command(2L, 1L, 2L, null, STOP_REQUEST, CANCELLED);
    assertThrows(IllegalStateException.class, () -> applied.consume(id(100)));
    assertThrows(IllegalStateException.class, () -> applied.cancel(STOP_REQUEST, CANCELLED));
    assertThrows(IllegalStateException.class, () -> cancelled.consume(id(100)));
    assertThrows(
        IllegalStateException.class,
        () -> cancelled.cancel(STOP_REQUEST, CANCELLED.plusSeconds(1)));
  }

  @Test
  void consumeRejectsNonPositiveTurnStartAndCancelRejectsPastTime() {
    ThreadCommand queued = command(1L, 1L, 1L, null, null, null);
    assertThrows(NullPointerException.class, () -> queued.consume(null));
    assertThrows(
        IllegalArgumentException.class, () -> queued.cancel(STOP_REQUEST, CREATED.minusSeconds(1)));
    assertThrows(NullPointerException.class, () -> queued.cancel(null, CANCELLED));
    assertThrows(NullPointerException.class, () -> queued.cancel(STOP_REQUEST, null));
  }

  /**
   * 构造 QUEUED/APPLIED/CANCELLED 之一的 ThreadCommand；cancel 必须以 (cancelRequestId, cancelledAt) 成对出现。
   */
  private static ThreadCommand command(
      long id,
      long threadId,
      long sequence,
      UUID consumedTurnStartEntryId,
      UUID cancelRequestId,
      Instant cancelledAt) {
    return new ThreadCommand(
        id(threadId),
        sequence,
        payload(),
        id(id),
        HASH,
        consumedTurnStartEntryId,
        cancelRequestId,
        cancelledAt,
        CREATED);
  }

  private static final String HASH = ThreadCommandPayloadJsonCodec.requestHash(payload());

  private static ThreadCommandPayload payload() {
    return new SetAgentCommandPayload("coding");
  }
}
