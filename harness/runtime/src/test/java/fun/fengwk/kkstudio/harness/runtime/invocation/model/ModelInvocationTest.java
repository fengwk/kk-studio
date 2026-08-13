package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.checkpoint;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.error;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.request;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.response;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** ModelInvocation 各 status 下持久化字段的不变式。 */
class ModelInvocationTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant UPDATED = CREATED.plusSeconds(10);

  private static ModelAttemptFailure failure(int attempt) {
    Instant failedAt = CREATED.plusSeconds(attempt * 2L);
    return new ModelAttemptFailure(
        attempt,
        attempt,
        "partial-" + attempt,
        "",
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "failure-" + attempt),
        failedAt,
        failedAt.plusSeconds(1));
  }

  @Test
  void acceptsValidStates() {
    ModelInvocation ready = invocation(ModelInvocationStatus.READY, 0, null, null, null, null);
    assertEquals(ModelInvocationStatus.READY, ready.status());
    assertNull(ready.streamCheckpoint());
    assertNull(ready.result());
    assertNull(ready.error());
    assertNull(ready.resultEntryId());
    assertEquals(id(1L), ready.id());
    assertEquals(id(1L), ready.threadId());
    assertEquals(id(1L), ready.turnStartEntryId());
    assertEquals(id(1L), ready.basisHeadEntryId());
    assertTrue(ready.request().yoloEnabled());

    ModelInvocation readyRetryWaiting =
        invocation(ModelInvocationStatus.READY, 1, null, null, null, null);
    assertEquals(1, readyRetryWaiting.attempt());

    ModelInvocation dispatchingFirstAttempt =
        invocation(ModelInvocationStatus.DISPATCHING, 0, null, null, null, null);
    assertEquals(ModelInvocationStatus.DISPATCHING, dispatchingFirstAttempt.status());
    assertNull(dispatchingFirstAttempt.streamCheckpoint());
    assertNull(dispatchingFirstAttempt.result());
    assertNull(dispatchingFirstAttempt.error());
    assertNull(dispatchingFirstAttempt.resultEntryId());

    ModelInvocation dispatchingRetry =
        invocation(ModelInvocationStatus.DISPATCHING, 1, null, null, null, null);
    assertEquals(1, dispatchingRetry.attempt());

    ModelInvocation running =
        invocation(ModelInvocationStatus.RUNNING, 1, checkpoint(1), null, null, null);
    assertEquals(1, running.attempt());
    assertEquals(1, running.streamCheckpoint().attempt());

    ModelInvocation runningWithoutCheckpoint =
        invocation(ModelInvocationStatus.RUNNING, 2, null, null, null, null);

    ModelInvocation succeeded =
        invocation(ModelInvocationStatus.SUCCEEDED, 1, null, response(), null, id(99L));
    assertEquals(response().text(), succeeded.result().text());
    assertEquals(id(99L), succeeded.resultEntryId());

    ModelInvocation succeededWithoutEntry =
        invocation(ModelInvocationStatus.SUCCEEDED, 1, null, response(), null, null);
    assertNull(succeededWithoutEntry.resultEntryId());

    ModelInvocation failed =
        invocation(ModelInvocationStatus.FAILED, 1, null, null, error(), id(99L));
    assertEquals("model boom", failed.error().message());

    ModelInvocation failedBeforeStart =
        invocation(ModelInvocationStatus.FAILED, 0, null, null, error(), null);
    assertEquals(0, failedBeforeStart.attempt());

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
        () -> invocation(ModelInvocationStatus.UNKNOWN, 0, null, null, error(), null));
  }

  @Test
  void rejectsInvalidDispatchingCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.DISPATCHING, 0, checkpoint(1), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.DISPATCHING, 0, null, response(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.DISPATCHING, 0, null, null, error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.DISPATCHING, 0, null, null, null, id(5L)));
  }

  @Test
  void rejectsInvalidIdentityAndTimeFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocation(
                id(1L),
                id(1L),
                id(1L),
                id(1L),
                request(),
                ModelInvocationStatus.READY,
                -1,
                null,
                null,
                null,
                null,
                List.of(),
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocation(
                id(1L),
                id(1L),
                id(1L),
                id(1L),
                request(),
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                List.of(),
                CREATED,
                CREATED.minusSeconds(1)));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelInvocation(
                id(1L),
                id(1L),
                id(1L),
                id(1L),
                null,
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                List.of(),
                CREATED,
                UPDATED));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelInvocation(
                id(1L), id(1L), id(1L), id(1L), request(), null, 0, null, null, null, null,
                List.of(), CREATED, UPDATED));
    assertThrows(
        NullPointerException.class,
        () ->
            new ModelInvocation(
                id(1L),
                id(1L),
                id(1L),
                id(1L),
                request(),
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                List.of(),
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
        () -> invocation(ModelInvocationStatus.READY, 0, null, null, null, id(5L)));
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
        () -> invocation(ModelInvocationStatus.RUNNING, 1, null, null, null, id(5L)));
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
        () ->
            invocation(ModelInvocationStatus.SUCCEEDED, 1, checkpoint(2), response(), null, null));
  }

  @Test
  void rejectsResultEntryIdOnNonTerminalStates() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.RUNNING, 1, null, null, null, id(5L)));
  }

  /** failedAttempts 必须形成连续 1..N 前缀，并遵守 retry schedule 的时间顺序。 */
  @Test
  void rejectsInvalidFailedAttemptSequenceAndTimeOrder() {
    ModelAttemptFailure first = failure(1);
    ModelAttemptFailure skipped =
        new ModelAttemptFailure(
            2,
            2,
            "partial-2",
            "",
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "failure-2"),
            first.retryAt(),
            first.retryAt().plusSeconds(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ModelInvocationStatus.READY, 2, null, null, null, null, List.of(skipped)));

    ModelAttemptFailure tooEarly =
        new ModelAttemptFailure(
            2,
            2,
            "partial-2",
            "",
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "failure-2"),
            first.retryAt().minusMillis(1),
            first.retryAt());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                ModelInvocationStatus.READY, 2, null, null, null, null, List.of(first, tooEarly)));
  }

  private static ModelInvocation invocation(
      ModelInvocationStatus status,
      int attempt,
      StreamCheckpoint streamCheckpoint,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId) {
    return new ModelInvocation(
        id(1L),
        id(1L),
        id(1L),
        id(1L),
        request(),
        status,
        attempt,
        streamCheckpoint,
        result,
        error,
        resultEntryId,
        failures(status, attempt),
        CREATED,
        UPDATED);
  }

  private static ModelInvocation invocation(
      ModelInvocationStatus status,
      int attempt,
      StreamCheckpoint streamCheckpoint,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      List<ModelAttemptFailure> failedAttempts) {
    return new ModelInvocation(
        id(1L),
        id(1L),
        id(1L),
        id(1L),
        request(),
        status,
        attempt,
        streamCheckpoint,
        result,
        error,
        resultEntryId,
        failedAttempts,
        CREATED,
        UPDATED);
  }

  private static List<ModelAttemptFailure> failures(ModelInvocationStatus status, int attempt) {
    int count =
        switch (status) {
          case READY, DISPATCHING -> attempt;
          case RUNNING, SUCCEEDED, UNKNOWN, FAILED, CANCELLED -> Math.max(0, attempt - 1);
        };
    return IntStream.rangeClosed(1, count).mapToObj(ModelInvocationTest::failure).toList();
  }
}
