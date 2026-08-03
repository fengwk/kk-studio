package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationTestData.CALL_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationTestData.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;

/** ToolInvocation per-status durable field invariants. */
class ToolInvocationTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant UPDATED = CREATED.plusSeconds(10);
  private static final Instant REQUESTED = CREATED;

  @Test
  void acceptsValidStates() {
    ToolInvocation waiting =
        invocation(ToolInvocationStatus.WAITING_APPROVAL, 0, requiredUndecided(), null, null, null);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, waiting.status());
    assertEquals(0, waiting.ordinal());
    assertEquals(1L, waiting.modelInvocationId());
    assertEquals(1L, waiting.assistantEntryId());
    assertTrue(waiting.approval().required());
    assertNull(waiting.result());
    assertNull(waiting.error());
    assertNull(waiting.resultEntryId());

    ToolInvocation readyWithoutApproval =
        invocation(ToolInvocationStatus.READY, 0, null, null, null, null);
    assertNull(readyWithoutApproval.approval());

    ToolInvocation readyNotRequired =
        invocation(ToolInvocationStatus.READY, 0, notRequired(), null, null, null);
    assertFalse(readyNotRequired.approval().required());

    ToolInvocation readyAllowed =
        invocation(ToolInvocationStatus.READY, 1, allowed(), null, null, null);
    assertEquals(ToolApprovalDecision.ALLOWED, readyAllowed.approval().decision());

    ToolInvocation running =
        invocation(ToolInvocationStatus.RUNNING, 1, allowed(), null, null, null);
    assertEquals(1, running.attempt());

    ToolInvocation runningNotRequired =
        invocation(ToolInvocationStatus.RUNNING, 2, notRequired(), null, null, null);
    assertEquals(2, runningNotRequired.attempt());

    ToolInvocation succeeded =
        invocation(ToolInvocationStatus.SUCCEEDED, 1, allowed(), result(), null, 99L);
    assertEquals(CALL_ID, succeeded.result().toolCallId());
    assertEquals(99L, succeeded.resultEntryId());

    ToolInvocation succeededWithoutEntry =
        invocation(ToolInvocationStatus.SUCCEEDED, 1, notRequired(), result(), null, null);
    assertNull(succeededWithoutEntry.resultEntryId());

    ToolInvocation failed = invocation(ToolInvocationStatus.FAILED, 1, null, null, error(), 99L);
    assertEquals("tool boom", failed.error().message());

    ToolInvocation cancelled =
        invocation(ToolInvocationStatus.CANCELLED, 1, null, null, error(), null);
    assertEquals(ToolInvocationStatus.CANCELLED, cancelled.status());

    ToolInvocation unknown =
        invocation(ToolInvocationStatus.UNKNOWN, 1, allowed(), null, error(), null);
    assertEquals(ToolInvocationStatus.UNKNOWN, unknown.status());
  }

  @Test
  void rejectsInvalidAttemptBoundaries() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                ToolInvocationStatus.WAITING_APPROVAL, 1, requiredUndecided(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 0, allowed(), result(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.UNKNOWN, 0, allowed(), null, error(), null));

    ToolInvocation failedBeforeStart =
        invocation(ToolInvocationStatus.FAILED, 0, null, null, error(), null);
    assertEquals(0, failedBeforeStart.attempt());

    ToolInvocation cancelledBeforeStart =
        invocation(ToolInvocationStatus.CANCELLED, 0, null, null, error(), null);
    assertEquals(0, cancelledBeforeStart.attempt());
  }

  @Test
  void requiresCompletedPreflightApproval() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, result(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.UNKNOWN, 1, null, null, error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, requiredUndecided(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, denied(), result(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.UNKNOWN, 1, denied(), null, error(), null));

    ToolInvocation failedDenied =
        invocation(ToolInvocationStatus.FAILED, 1, denied(), null, error(), null);
    assertEquals(ToolApprovalDecision.DENIED, failedDenied.approval().decision());

    ToolInvocation cancelledUndecided =
        invocation(ToolInvocationStatus.CANCELLED, 1, requiredUndecided(), null, error(), null);
    assertTrue(cancelledUndecided.approval().isUndecided());
  }

  @Test
  void rejectsInvalidIdentityOrdinalAttemptAndTimeFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                0L,
                1L,
                1L,
                0,
                request("bash", "{}"),
                ToolInvocationStatus.READY,
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
            new ToolInvocation(
                1L,
                0L,
                1L,
                0,
                request("bash", "{}"),
                ToolInvocationStatus.READY,
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
            new ToolInvocation(
                1L,
                1L,
                0L,
                0,
                request("bash", "{}"),
                ToolInvocationStatus.READY,
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
            new ToolInvocation(
                1L,
                1L,
                1L,
                -1,
                request("bash", "{}"),
                ToolInvocationStatus.READY,
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
            new ToolInvocation(
                1L,
                1L,
                1L,
                0,
                request("bash", "{}"),
                ToolInvocationStatus.READY,
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
            new ToolInvocation(
                1L,
                1L,
                1L,
                0,
                request("bash", "{}"),
                ToolInvocationStatus.READY,
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
            new ToolInvocation(
                1L,
                1L,
                1L,
                0,
                null,
                ToolInvocationStatus.READY,
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
            new ToolInvocation(
                1L,
                1L,
                1L,
                0,
                request("bash", "{}"),
                null,
                0,
                null,
                null,
                null,
                null,
                CREATED,
                UPDATED));
  }

  @Test
  void rejectsInvalidWaitingApprovalCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.WAITING_APPROVAL, 0, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(ToolInvocationStatus.WAITING_APPROVAL, 0, notRequired(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.WAITING_APPROVAL, 0, allowed(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                ToolInvocationStatus.WAITING_APPROVAL,
                0,
                requiredUndecided(),
                result(),
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                ToolInvocationStatus.WAITING_APPROVAL,
                0,
                requiredUndecided(),
                null,
                error(),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                ToolInvocationStatus.WAITING_APPROVAL, 0, requiredUndecided(), null, null, 5L));
  }

  @Test
  void rejectsInvalidReadyCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.READY, 0, requiredUndecided(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.READY, 0, denied(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.READY, 0, null, result(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.READY, 0, null, null, error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.READY, 0, null, null, null, 5L));
  }

  @Test
  void rejectsInvalidRunningCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 0, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, requiredUndecided(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, denied(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, null, result(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, null, null, error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, null, null, null, 5L));
  }

  @Test
  void rejectsInvalidTerminalCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, mismatchedResult(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, result(), error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.FAILED, 1, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.FAILED, 1, null, result(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.FAILED, 1, null, result(), error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.CANCELLED, 1, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.UNKNOWN, 1, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.UNKNOWN, 1, allowed(), result(), error(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, result(), null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, result(), null, -1L));
  }

  private static ToolInvocation invocation(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      Long resultEntryId) {
    return new ToolInvocation(
        1L,
        1L,
        1L,
        0,
        request("bash", "{}"),
        status,
        attempt,
        approval,
        result,
        error,
        resultEntryId,
        CREATED,
        UPDATED);
  }

  private static ToolApproval requiredUndecided() {
    return new ToolApproval(true, null, null, null, null, REQUESTED, null);
  }

  private static ToolApproval notRequired() {
    return new ToolApproval(false, null, null, null, null, null, null);
  }

  private static ToolApproval allowed() {
    return new ToolApproval(
        true,
        ToolApprovalDecision.ALLOWED,
        "d-1",
        "actor",
        null,
        REQUESTED,
        REQUESTED.plusSeconds(1));
  }

  private static ToolApproval denied() {
    return new ToolApproval(
        true,
        ToolApprovalDecision.DENIED,
        "d-2",
        "actor",
        null,
        REQUESTED,
        REQUESTED.plusSeconds(1));
  }

  private static ToolResult result() {
    return new ToolResult(CALL_ID, List.of(), false, "{}", false);
  }

  private static ToolResult mismatchedResult() {
    return new ToolResult("other-call", List.of(), false, "{}", false);
  }

  private static ToolInvocationError error() {
    return new ToolInvocationError("FAILED", "tool boom");
  }
}
