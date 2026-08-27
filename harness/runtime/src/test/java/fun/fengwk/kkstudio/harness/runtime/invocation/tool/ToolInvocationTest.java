package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationTestData.CALL_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationTestData.call;
import static fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationTestData.host;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;

/** ToolInvocation 各 status 下持久化字段的不变式。 */
class ToolInvocationTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant UPDATED = CREATED.plusSeconds(10);
  private static final Instant REQUESTED = CREATED;

  @Test
  void acceptsValidStates() {
    ToolInvocation waiting =
        invocation(ToolInvocationStatus.WAITING_APPROVAL, 0, requiredUndecided(), null, null);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, waiting.status());
    assertEquals(0, waiting.ordinal());
    assertEquals(id(1L), waiting.modelInvocationId());
    assertEquals(id(1L), waiting.assistantEntryId());
    assertTrue(waiting.approval().required());
    assertNull(waiting.result());
    assertNull(waiting.error());

    ToolInvocation readyWithoutApproval =
        invocation(ToolInvocationStatus.READY, 0, null, null, null);
    assertNull(readyWithoutApproval.approval());

    ToolInvocation readyNotRequired =
        invocation(ToolInvocationStatus.READY, 0, notRequired(), null, null);
    assertFalse(readyNotRequired.approval().required());

    ToolInvocation readyAllowed = invocation(ToolInvocationStatus.READY, 1, allowed(), null, null);
    assertEquals(ToolApprovalDecision.ALLOWED, readyAllowed.approval().decision());

    ToolInvocation dispatchingNotRequired =
        invocation(ToolInvocationStatus.DISPATCHING, 0, notRequired(), null, null);
    assertEquals(ToolInvocationStatus.DISPATCHING, dispatchingNotRequired.status());
    assertNull(dispatchingNotRequired.result());
    assertNull(dispatchingNotRequired.error());

    ToolInvocation dispatchingAllowed =
        invocation(ToolInvocationStatus.DISPATCHING, 2, allowed(), null, null);
    assertEquals(2, dispatchingAllowed.attempt());

    ToolInvocation running = invocation(ToolInvocationStatus.RUNNING, 1, allowed(), null, null);
    assertEquals(1, running.attempt());

    ToolInvocation runningNotRequired =
        invocation(ToolInvocationStatus.RUNNING, 2, notRequired(), null, null);
    assertEquals(2, runningNotRequired.attempt());

    ToolInvocation succeeded =
        invocation(ToolInvocationStatus.SUCCEEDED, 1, allowed(), result(), null);
    assertEquals(CALL_ID, succeeded.result().toolCallId());

    ToolInvocation succeededWithoutEntry =
        invocation(ToolInvocationStatus.SUCCEEDED, 1, notRequired(), result(), null);

    ToolInvocation failed = invocation(ToolInvocationStatus.FAILED, 1, null, null, error());
    assertEquals("tool boom", failed.error().message());

    ToolInvocation cancelled = invocation(ToolInvocationStatus.CANCELLED, 1, null, null, error());
    assertEquals(ToolInvocationStatus.CANCELLED, cancelled.status());

    ToolInvocation unknown = invocation(ToolInvocationStatus.UNKNOWN, 1, allowed(), null, error());
    assertEquals(ToolInvocationStatus.UNKNOWN, unknown.status());
  }

  @Test
  void rejectsInvalidAttemptBoundaries() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(ToolInvocationStatus.WAITING_APPROVAL, 1, requiredUndecided(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 0, allowed(), result(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.UNKNOWN, 0, allowed(), null, error()));

    ToolInvocation failedBeforeStart =
        invocation(ToolInvocationStatus.FAILED, 0, null, null, error());
    assertEquals(0, failedBeforeStart.attempt());

    ToolInvocation cancelledBeforeStart =
        invocation(ToolInvocationStatus.CANCELLED, 0, null, null, error());
    assertEquals(0, cancelledBeforeStart.attempt());
  }

  @Test
  void requiresCompletedPreflightApproval() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.DISPATCHING, 0, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.DISPATCHING, 0, requiredUndecided(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.DISPATCHING, 0, denied(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, result(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.UNKNOWN, 1, null, null, error()));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, requiredUndecided(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, denied(), result(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.UNKNOWN, 1, denied(), null, error()));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.FAILED, 0, requiredUndecided(), null, error()));

    ToolInvocation failedDenied =
        invocation(ToolInvocationStatus.FAILED, 1, denied(), null, error());
    assertEquals(ToolApprovalDecision.DENIED, failedDenied.approval().decision());

    ToolInvocation cancelledUndecided =
        invocation(ToolInvocationStatus.CANCELLED, 1, requiredUndecided(), null, error());
    assertTrue(cancelledUndecided.approval().isUndecided());
  }

  @Test
  void rejectsInvalidIdentityOrdinalAttemptAndTimeFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                id(1L),
                id(1L),
                id(1L),
                -1,
                call("bash", "{}"),
                host("bash"),
                ToolInvocationStatus.READY,
                0,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                id(1L),
                id(1L),
                id(1L),
                0,
                call("bash", "{}"),
                host("bash"),
                ToolInvocationStatus.READY,
                -1,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                id(1L),
                id(1L),
                id(1L),
                0,
                call("bash", "{}"),
                host("bash"),
                ToolInvocationStatus.READY,
                0,
                null,
                null,
                null,
                CREATED,
                CREATED.minusSeconds(1)));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolInvocation(
                id(1L),
                id(1L),
                id(1L),
                0,
                null,
                host("bash"),
                ToolInvocationStatus.READY,
                0,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                id(1L),
                id(1L),
                id(1L),
                0,
                call("bash", "{}"),
                null,
                ToolInvocationStatus.READY,
                0,
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
        () -> invocation(ToolInvocationStatus.WAITING_APPROVAL, 0, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.WAITING_APPROVAL, 0, notRequired(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.WAITING_APPROVAL, 0, allowed(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                ToolInvocationStatus.WAITING_APPROVAL, 0, requiredUndecided(), result(), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                ToolInvocationStatus.WAITING_APPROVAL, 0, requiredUndecided(), null, error()));
  }

  @Test
  void rejectsInvalidReadyCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.READY, 0, requiredUndecided(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.READY, 0, denied(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.READY, 0, null, result(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.READY, 0, null, null, error()));
  }

  @Test
  void rejectsInvalidRunningCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 0, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, requiredUndecided(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, denied(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, null, result(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.RUNNING, 1, null, null, error()));
  }

  @Test
  void rejectsInvalidTerminalCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, mismatchedResult(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, result(), error()));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.FAILED, 1, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.FAILED, 1, null, result(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.FAILED, 1, null, result(), error()));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.CANCELLED, 1, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.UNKNOWN, 1, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.UNKNOWN, 1, allowed(), result(), error()));
  }

  /** durable 不变量：非空 binding 必须匹配 call 的 toolName（codec 解码的 READY 无法再携带错配 binding）。 */
  @Test
  void rejectsBindingMismatchingToolNameOnExecutableStates() {
    ToolCall mismatchedCall = call("other-tool", "{}");
    ToolBinding mismatchedBinding = host("bash");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                id(1L),
                id(1L),
                id(1L),
                0,
                mismatchedCall,
                mismatchedBinding,
                ToolInvocationStatus.READY,
                0,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                id(1L),
                id(1L),
                id(1L),
                0,
                mismatchedCall,
                mismatchedBinding,
                ToolInvocationStatus.SUCCEEDED,
                1,
                null,
                result(),
                null,
                CREATED,
                UPDATED));
  }

  /** durable 不变量：除 immediate FAILED 外的状态必须通过 binding schema（codec 解码的 READY 无法携带非法参数）。 */
  @Test
  void rejectsSchemaInvalidArgumentsOnExecutableStates() {
    ToolCall schemaInvalidCall = call("bash", "{\"unexpected\":1}");
    ToolBinding schemaInvalidBinding = host("bash");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                id(1L),
                id(1L),
                id(1L),
                0,
                schemaInvalidCall,
                schemaInvalidBinding,
                ToolInvocationStatus.READY,
                0,
                null,
                null,
                null,
                CREATED,
                UPDATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                id(1L),
                id(1L),
                id(1L),
                0,
                schemaInvalidCall,
                schemaInvalidBinding,
                ToolInvocationStatus.FAILED,
                1,
                null,
                null,
                error(),
                CREATED,
                UPDATED));
  }

  /**
   * immediate FAILED（attempt 0）是 INVALID_TOOL_ARGUMENTS / MODEL_OUTPUT_TRUNCATED / UNKNOWN_TOOL
   * 的合法形态。
   */
  @Test
  void acceptsImmediateFailedWithSchemaInvalidArgumentsOrNullBinding() {
    ToolInvocation schemaInvalid =
        new ToolInvocation(
            id(1L),
            id(1L),
            id(1L),
            0,
            call("bash", "{\"unexpected\":1}"),
            host("bash"),
            ToolInvocationStatus.FAILED,
            0,
            null,
            null,
            error(),
            CREATED,
            UPDATED);
    assertEquals(ToolInvocationStatus.FAILED, schemaInvalid.status());
    assertEquals(0, schemaInvalid.attempt());

    ToolInvocation unbound =
        new ToolInvocation(
            id(1L),
            id(1L),
            id(1L),
            0,
            call("unknown-tool", "{}"),
            null,
            ToolInvocationStatus.FAILED,
            0,
            null,
            null,
            error(),
            CREATED,
            UPDATED);
    assertNull(unbound.binding());
    // 非 immediate FAILED（attempt>0）不允许 null binding。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                id(1L),
                id(1L),
                id(1L),
                0,
                call("unknown-tool", "{}"),
                null,
                ToolInvocationStatus.FAILED,
                1,
                null,
                null,
                error(),
                CREATED,
                UPDATED));
  }

  private static ToolInvocation invocation(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error) {
    return new ToolInvocation(
        id(1L),
        id(1L),
        id(1L),
        0,
        call("bash", "{}"),
        host("bash"),
        status,
        attempt,
        approval,
        result,
        error,
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
        id(1L),
        "actor",
        null,
        REQUESTED,
        REQUESTED.plusSeconds(1));
  }

  private static ToolApproval denied() {
    return new ToolApproval(
        true,
        ToolApprovalDecision.DENIED,
        id(2L),
        "actor",
        null,
        REQUESTED,
        REQUESTED.plusSeconds(1));
  }

  private static ToolResult result() {
    return new ToolResult(CALL_ID, List.of(), false, "{}");
  }

  private static ToolResult mismatchedResult() {
    return new ToolResult("other-call", List.of(), false, "{}");
  }

  private static ToolInvocationError error() {
    return new ToolInvocationError("FAILED", "tool boom");
  }
}
