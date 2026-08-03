package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.checkpoint;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.error;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.request;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;

import java.time.Instant;

/** ModelInvocation per-status durable field invariants. */
class ModelInvocationTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant UPDATED = CREATED.plusSeconds(10);

  @Test
  void acceptsValidStates() {
    ModelInvocation ready = invocation(ModelInvocationStatus.READY, 0, null, null, null, null);
    assertEquals(ModelInvocationStatus.READY, ready.status());
    assertNull(ready.streamCheckpoint());
    assertNull(ready.result());
    assertNull(ready.error());
    assertNull(ready.resultEntryId());
    assertEquals(1L, ready.id());
    assertEquals(1L, ready.threadId());
    assertEquals(1L, ready.turnStartEntryId());
    assertEquals(1L, ready.basisHeadEntryId());
    assertTrue(ready.request().yoloEnabled());

    ModelInvocation readyRetryWaiting =
        invocation(ModelInvocationStatus.READY, 1, null, null, null, null);
    assertEquals(1, readyRetryWaiting.attempt());

    ModelInvocation running =
        invocation(ModelInvocationStatus.RUNNING, 1, checkpoint(1), null, null, null);
    assertEquals(1, running.attempt());
    assertEquals(1, running.streamCheckpoint().attempt());

    ModelInvocation runningWithoutCheckpoint =
        invocation(ModelInvocationStatus.RUNNING, 2, null, null, null, null);

    ModelInvocation succeeded =
        invocation(ModelInvocationStatus.SUCCEEDED, 1, checkpoint(1), response(), null, 99L);
    assertEquals(response().text(), succeeded.result().text());
    assertEquals(99L, succeeded.resultEntryId());

    ModelInvocation succeededWithoutEntry =
        invocation(ModelInvocationStatus.SUCCEEDED, 1, null, response(), null, null);
    assertNull(succeededWithoutEntry.resultEntryId());

    ModelInvocation failed = invocation(ModelInvocationStatus.FAILED, 1, null, null, error(), 99L);
    assertEquals("model boom", failed.error().message());

    ModelInvocation cancelled =
        invocation(ModelInvocationStatus.CANCELLED, 1, null, null, error(), null);
    assertEquals(ModelInvocationStatus.CANCELLED, cancelled.status());

    ModelInvocation cancelledBeforeStart =
        invocation(ModelInvocationStatus.CANCELLED, 0, null, null, error(), null);
    assertEquals(0, cancelledBeforeStart.attempt());

    ModelInvocation unknown =
        invocation(ModelInvocationStatus.UNKNOWN, 1, null, null, error(), null);
    assertEquals(ModelInvocationStatus.UNKNOWN, unknown.status());
  }

  @Test
  void rejectsTerminalAttemptZero() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.SUCCEEDED, 0, null, response(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.FAILED, 0, null, null, error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.UNKNOWN, 0, null, null, error(), null));
  }

  @Test
  void rejectsInvalidIdentityAndTimeFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocation(
                0L,
                1L,
                1L,
                1L,
                request(),
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocation(
                1L,
                0L,
                1L,
                1L,
                request(),
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocation(
                1L,
                1L,
                0L,
                1L,
                request(),
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocation(
                1L,
                1L,
                1L,
                0L,
                request(),
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocation(
                1L,
                1L,
                1L,
                1L,
                request(),
                ModelInvocationStatus.READY,
                -1,
                null,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocation(
                1L,
                1L,
                1L,
                1L,
                request(),
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                CREATED,
                CREATED.minusSeconds(1)));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelInvocation(
                1L,
                1L,
                1L,
                1L,
                null,
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelInvocation(
                1L, 1L, 1L, 1L, request(), null, 0, null, null, null, null, CREATED, UPDATED));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelInvocation(
                1L,
                1L,
                1L,
                1L,
                request(),
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                null,
                UPDATED));
  }

  @Test
  void rejectsInvalidReadyCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.READY, 0, checkpoint(1), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.READY, 1, checkpoint(1), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.READY, 0, null, response(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.READY, 0, null, null, error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.READY, 0, null, null, null, 5L));
  }

  @Test
  void rejectsInvalidRunningCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.RUNNING, 0, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.RUNNING, 1, null, response(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.RUNNING, 1, null, null, error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.RUNNING, 1, null, null, null, 5L));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.RUNNING, 1, checkpoint(2), null, null, null));
  }

  @Test
  void rejectsInvalidTerminalCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.SUCCEEDED, 1, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.SUCCEEDED, 1, null, response(), error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.FAILED, 1, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.FAILED, 1, null, response(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.FAILED, 1, null, response(), error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.CANCELLED, 1, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.CANCELLED, 1, null, response(), error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.UNKNOWN, 1, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.SUCCEEDED, 1, null, response(), null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(ModelInvocationStatus.SUCCEEDED, 1, checkpoint(2), response(), null, null));
  }

  @Test
  void rejectsResultEntryIdOnNonTerminalStates() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.RUNNING, 1, null, null, null, 5L));
  }

  private static ModelInvocation invocation(
      ModelInvocationStatus status,
      int attempt,
      StreamCheckpoint streamCheckpoint,
      ProviderResponse result,
      ModelInvocationError error,
      Long resultEntryId) {
    return new ModelInvocation(
        1L,
        1L,
        1L,
        1L,
        request(),
        status,
        attempt,
        streamCheckpoint,
        result,
        error,
        resultEntryId,
        CREATED,
        UPDATED);
  }
}
