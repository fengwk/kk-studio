package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** Thread mailbox input state-machine and idempotency contract. */
class ThreadInputTest {
  private static final Instant CREATED_AT = Instant.parse("2026-07-18T00:00:00Z");
  private static final Instant RESOLVED_AT = CREATED_AT.plusSeconds(30);

  /**
   * Each terminal state has exactly its required resolution fields and reports its state helper.
   */
  @Test
  void acceptsEachLegalResolutionStateAndPreservesMailboxIdentity() {
    ThreadInput queued = input(ThreadInputStatus.QUEUED, null, null, null);
    ThreadInput applied = input(ThreadInputStatus.APPLIED, 31L, RESOLVED_AT, null);
    ThreadInput cancelled = input(ThreadInputStatus.CANCELLED, null, RESOLVED_AT, 41L);

    assertEquals(11L, queued.id());
    assertEquals(12L, queued.threadId());
    assertEquals(13L, queued.sequence());
    assertEquals(ThreadInputType.USER_MESSAGE, queued.inputType());
    assertEquals("{\"text\":\"hello\"}", queued.payloadJson());
    assertEquals("client-message-1", queued.clientMessageId());
    assertEquals(CREATED_AT, queued.createdAt());
    assertTrue(queued.queued());
    assertFalse(queued.applied());
    assertFalse(queued.cancelled());
    assertFalse(applied.queued());
    assertTrue(applied.applied());
    assertFalse(applied.cancelled());
    assertEquals(31L, applied.appliedEntryId());
    assertEquals(RESOLVED_AT, applied.resolvedAt());
    assertFalse(cancelled.queued());
    assertFalse(cancelled.applied());
    assertTrue(cancelled.cancelled());
    assertEquals(41L, cancelled.cancelledByStopId());
  }

  /** Resolution fields cannot describe more than one state or omit a terminal-state requirement. */
  @Test
  void rejectsIllegalResolutionFieldCombinations() {
    assertInvalid(ThreadInputStatus.APPLIED, -1L, RESOLVED_AT, null);
    assertInvalid(ThreadInputStatus.CANCELLED, null, RESOLVED_AT, -1L);
    assertInvalid(ThreadInputStatus.QUEUED, 31L, null, null);
    assertInvalid(ThreadInputStatus.QUEUED, null, RESOLVED_AT, null);
    assertInvalid(ThreadInputStatus.QUEUED, null, null, 41L);
    assertInvalid(ThreadInputStatus.APPLIED, null, RESOLVED_AT, null);
    assertInvalid(ThreadInputStatus.APPLIED, 31L, null, null);
    assertInvalid(ThreadInputStatus.APPLIED, 31L, RESOLVED_AT, 41L);
    assertInvalid(ThreadInputStatus.CANCELLED, null, null, 41L);
    assertInvalid(ThreadInputStatus.CANCELLED, null, RESOLVED_AT, null);
    assertInvalid(ThreadInputStatus.CANCELLED, 31L, RESOLVED_AT, 41L);
  }

  /** Mailbox ordering and client idempotency data must be complete before state validation. */
  @Test
  void rejectsInvalidIdentitySequencePayloadAndClientMessageId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                0,
                12,
                13,
                ThreadInputType.USER_MESSAGE,
                "{}",
                "client",
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                CREATED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                11,
                0,
                13,
                ThreadInputType.USER_MESSAGE,
                "{}",
                "client",
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                CREATED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                11,
                12,
                0,
                ThreadInputType.USER_MESSAGE,
                "{}",
                "client",
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                CREATED_AT));
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadInput(
                11,
                12,
                13,
                null,
                "{}",
                "client",
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                CREATED_AT));
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadInput(
                11,
                12,
                13,
                ThreadInputType.USER_MESSAGE,
                null,
                "client",
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                CREATED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                11,
                12,
                13,
                ThreadInputType.USER_MESSAGE,
                " ",
                "client",
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                CREATED_AT));
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadInput(
                11,
                12,
                13,
                ThreadInputType.USER_MESSAGE,
                "{}",
                null,
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                CREATED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                11,
                12,
                13,
                ThreadInputType.USER_MESSAGE,
                "{}",
                " ",
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                CREATED_AT));
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadInput(
                11,
                12,
                13,
                ThreadInputType.USER_MESSAGE,
                "{}",
                "client",
                null,
                null,
                null,
                null,
                CREATED_AT));
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadInput(
                11,
                12,
                13,
                ThreadInputType.USER_MESSAGE,
                "{}",
                "client",
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                null));
  }

  private static ThreadInput input(
      ThreadInputStatus status, Long appliedEntryId, Instant resolvedAt, Long cancelledByStopId) {
    return new ThreadInput(
        11,
        12,
        13,
        ThreadInputType.USER_MESSAGE,
        "{\"text\":\"hello\"}",
        "client-message-1",
        status,
        appliedEntryId,
        resolvedAt,
        cancelledByStopId,
        CREATED_AT);
  }

  private static void assertInvalid(
      ThreadInputStatus status, Long appliedEntryId, Instant resolvedAt, Long cancelledByStopId) {
    assertThrows(
        IllegalArgumentException.class,
        () -> input(status, appliedEntryId, resolvedAt, cancelledByStopId));
  }
}
