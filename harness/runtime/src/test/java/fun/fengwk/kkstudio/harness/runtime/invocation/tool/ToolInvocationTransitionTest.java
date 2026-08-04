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
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;

/** ToolInvocation pure transition methods and the shared transition validation. */
class ToolInvocationTransitionTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant REQUESTED = CREATED;
  private static final Instant DECIDED = CREATED.plusSeconds(1);
  private static final Instant T1 = CREATED.plusSeconds(1);
  private static final Instant T2 = CREATED.plusSeconds(2);
  private static final Instant T3 = CREATED.plusSeconds(3);

  private static ToolInvocation ready(int attempt, ToolApproval approval) {
    return invocation(ToolInvocationStatus.READY, attempt, approval, null, null, null);
  }

  private static ToolInvocation waiting() {
    return invocation(ToolInvocationStatus.WAITING_APPROVAL, 0, undecided(), null, null, null);
  }

  private static ToolInvocation dispatching(int attempt, ToolApproval approval) {
    return invocation(ToolInvocationStatus.DISPATCHING, attempt, approval, null, null, null);
  }

  private static ToolInvocation running(int attempt, ToolApproval approval) {
    return invocation(ToolInvocationStatus.RUNNING, attempt, approval, null, null, null);
  }

  private static ToolInvocation succeeded(int attempt) {
    return invocation(ToolInvocationStatus.SUCCEEDED, attempt, allowed(), result(), null, null);
  }

  private static ToolInvocation failed(int attempt) {
    return invocation(ToolInvocationStatus.FAILED, attempt, null, null, error(), null);
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
        CREATED);
  }

  private static ToolApproval undecided() {
    return ToolApproval.request(REQUESTED, null);
  }

  private static ToolApproval allowed() {
    return new ToolApproval(
        true, ToolApprovalDecision.ALLOWED, "d-1", "actor", null, REQUESTED, DECIDED);
  }

  private static ToolApproval denied() {
    return new ToolApproval(
        true, ToolApprovalDecision.DENIED, "d-2", "actor", null, REQUESTED, DECIDED);
  }

  private static ToolResult result() {
    return new ToolResult(CALL_ID, List.of(), false, "{}", false);
  }

  private static ToolInvocationError error() {
    return new ToolInvocationError("FAILED", "tool boom");
  }

  @Test
  void markApprovalNotRequiredOnlyFromReadyWithoutApproval() {
    ToolInvocation next = ready(0, null).markApprovalNotRequired(T1);
    assertEquals(ToolInvocationStatus.READY, next.status());
    assertFalse(next.approval().required());
    assertNull(next.approval().decision());
    assertThrows(
        IllegalArgumentException.class, () -> ready(0, allowed()).markApprovalNotRequired(T1));
    assertThrows(IllegalArgumentException.class, () -> waiting().markApprovalNotRequired(T1));
  }

  @Test
  void requestApprovalAsksOnlyFromReadyWithoutApproval() {
    ToolInvocation next = ready(0, null).requestApproval("needs ok", T1);
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, next.status());
    assertTrue(next.approval().isUndecided());
    assertEquals(T1, next.approval().requestedAt());
    assertEquals("needs ok", next.approval().reason());
    assertEquals(0, next.attempt());
    assertThrows(
        IllegalArgumentException.class, () -> ready(0, allowed()).requestApproval(null, T1));
    assertThrows(IllegalArgumentException.class, () -> waiting().requestApproval(null, T1));
  }

  @Test
  void decideApprovalAllowsBackToReady() {
    ToolInvocation next =
        waiting().decideApproval(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED, T1);
    assertEquals(ToolInvocationStatus.READY, next.status());
    assertEquals(ToolApprovalDecision.ALLOWED, next.approval().decision());
    assertEquals("d-1", next.approval().decisionId());
    assertEquals(REQUESTED, next.approval().requestedAt());
    assertEquals(DECIDED, next.approval().decidedAt());
    assertNull(next.error());
  }

  @Test
  void decideApprovalDeniesToFailedWithDeterministicError() {
    ToolInvocation next =
        waiting()
            .decideApproval(
                ToolApprovalDecision.DENIED, "d-2", "actor", "not allowed", DECIDED, T1);
    assertEquals(ToolInvocationStatus.FAILED, next.status());
    assertEquals(ToolApprovalDecision.DENIED, next.approval().decision());
    assertEquals("DENIED", next.error().kind());
    assertEquals("not allowed", next.error().message());
    assertEquals(0, next.attempt());
  }

  @Test
  void decideApprovalIsExactIdempotentAndConflictsOnRewrites() {
    ToolInvocation decided =
        waiting().decideApproval(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED, T1);
    // exact replay of the same decision payload is idempotent
    ToolInvocation replay =
        decided.decideApproval(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED, T2);
    assertEquals(decided.approval(), replay.approval());
    assertEquals(ToolInvocationStatus.READY, replay.status());
    // same decisionId with a different payload conflicts
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decided.decideApproval(
                ToolApprovalDecision.ALLOWED, "d-1", "other-actor", null, DECIDED, T2));
    // a different existing decision conflicts
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decided.decideApproval(ToolApprovalDecision.DENIED, "d-9", "actor", null, DECIDED, T2));
    // a denied terminal replay is also idempotent: every decision fact stays frozen, only the
    // invocation updatedAt advances to the replay time
    ToolInvocation deniedTerminal =
        waiting().decideApproval(ToolApprovalDecision.DENIED, "d-2", "actor", null, DECIDED, T1);
    ToolInvocation deniedReplay =
        deniedTerminal.decideApproval(
            ToolApprovalDecision.DENIED, "d-2", "actor", null, DECIDED, T2);
    assertEquals(deniedTerminal.status(), deniedReplay.status());
    assertEquals(deniedTerminal.attempt(), deniedReplay.attempt());
    assertEquals(deniedTerminal.approval(), deniedReplay.approval());
    assertEquals(deniedTerminal.error(), deniedReplay.error());
    assertEquals(T2, deniedReplay.updatedAt());
    // decide without an approval is rejected
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ready(0, null)
                .decideApproval(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED, T1));
  }

  @Test
  void beginDispatchRequiresCompletedPreflight() {
    ToolInvocation notRequired = ready(0, null).markApprovalNotRequired(T1).beginDispatch(T2);
    assertEquals(ToolInvocationStatus.DISPATCHING, notRequired.status());
    assertEquals(0, notRequired.attempt());
    ToolInvocation allowed = ready(1, allowed()).beginDispatch(T1);
    assertEquals(ToolInvocationStatus.DISPATCHING, allowed.status());
    assertEquals(1, allowed.attempt());
    assertThrows(IllegalArgumentException.class, () -> ready(0, null).beginDispatch(T1));
    assertThrows(
        IllegalArgumentException.class,
        () -> ready(0, null).markApprovalNotRequired(T1).beginDispatch(T2).beginDispatch(T3));
  }

  @Test
  void dispatchBusyAndRejectDispatchLeaveAttemptUnchanged() {
    ToolInvocation busy = dispatching(2, allowed()).dispatchBusy(T1);
    assertEquals(ToolInvocationStatus.READY, busy.status());
    assertEquals(2, busy.attempt());
    assertEquals(allowed(), busy.approval());
    ToolInvocation rejected = dispatching(0, allowed()).rejectDispatch(error(), T1);
    assertEquals(ToolInvocationStatus.FAILED, rejected.status());
    assertEquals(0, rejected.attempt());
    assertThrows(IllegalArgumentException.class, () -> ready(0, null).dispatchBusy(T1));
    assertThrows(IllegalArgumentException.class, () -> ready(0, null).rejectDispatch(error(), T1));
  }

  @Test
  void markRunningConfirmsTheStartAndAdvancesAttemptByOne() {
    ToolInvocation next = dispatching(2, allowed()).markRunning(T1);
    assertEquals(ToolInvocationStatus.RUNNING, next.status());
    assertEquals(3, next.attempt());
    assertThrows(IllegalArgumentException.class, () -> ready(0, null).markRunning(T1));
  }

  @Test
  void succeedCompletesRunning() {
    ToolInvocation next = running(1, allowed()).succeed(result(), T1);
    assertEquals(ToolInvocationStatus.SUCCEEDED, next.status());
    assertEquals(1, next.attempt());
    assertEquals(result(), next.result());
    assertEquals(allowed(), next.approval());
    assertThrows(IllegalArgumentException.class, () -> ready(0, null).succeed(result(), T1));
    assertThrows(
        IllegalArgumentException.class, () -> dispatching(0, allowed()).succeed(result(), T1));
  }

  @Test
  void failTerminatesFromReadyAndRunningKeepingAttempt() {
    assertEquals(ToolInvocationStatus.FAILED, ready(0, null).fail(error(), T1).status());
    assertEquals(0, ready(0, null).fail(error(), T1).attempt());
    assertEquals(2, running(2, allowed()).fail(error(), T1).attempt());
    assertThrows(IllegalArgumentException.class, () -> succeeded(1).fail(error(), T1));
    // DISPATCHING may only be failed through rejectDispatch, never fail()
    assertThrows(IllegalArgumentException.class, () -> dispatching(1, allowed()).fail(error(), T1));
    // WAITING_APPROVAL cannot fail directly: the undecided approval must be decided (DENIED ->
    // FAILED) or stopped as CANCELLED
    assertThrows(IllegalArgumentException.class, () -> waiting().fail(error(), T1));
  }

  @Test
  void cancelTerminatesFromWaitingReadyAndRunningButNotDispatching() {
    assertEquals(ToolInvocationStatus.CANCELLED, waiting().cancel(error(), T1).status());
    assertEquals(0, waiting().cancel(error(), T1).attempt());
    assertTrue(waiting().cancel(error(), T1).approval().isUndecided());
    assertEquals(0, ready(0, null).cancel(error(), T1).attempt());
    assertEquals(2, running(2, allowed()).cancel(error(), T1).attempt());
    // DISPATCHING is the window where the execution may already have started: UNKNOWN is the only
    // honest termination, CANCELLED is rejected
    assertThrows(
        IllegalArgumentException.class, () -> dispatching(1, allowed()).cancel(error(), T1));
    assertThrows(IllegalArgumentException.class, () -> succeeded(1).cancel(error(), T1));
  }

  @Test
  void retryReadyReturnsToReadyKeepingAttemptAndApproval() {
    ToolInvocation next = running(2, allowed()).retryReady(T1);
    assertEquals(ToolInvocationStatus.READY, next.status());
    assertEquals(2, next.attempt());
    assertEquals(allowed(), next.approval());
    assertNull(next.result());
    assertNull(next.error());
    assertThrows(IllegalArgumentException.class, () -> ready(0, null).retryReady(T1));
    assertThrows(IllegalArgumentException.class, () -> dispatching(0, allowed()).retryReady(T1));
    assertThrows(IllegalArgumentException.class, () -> waiting().retryReady(T1));
    assertThrows(IllegalArgumentException.class, () -> succeeded(1).retryReady(T1));
  }

  @Test
  void approvalMayOnlyBeIntroducedAsNotRequiredOrUndecidedRequest() {
    ToolInvocation stored = ready(0, null);
    // direct-record injection of a decided approval from a null stored approval is rejected
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(stored, ready(0, allowed())));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(stored, ready(0, denied())));
    // READY -> READY with not-required and READY -> WAITING_APPROVAL with undecided are legal
    ToolInvocation.validateTransition(stored, stored.markApprovalNotRequired(T1));
    ToolInvocation.validateTransition(stored, stored.requestApproval("why", T1));
    // introducing an approval on any other edge is rejected
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                stored,
                invocation(ToolInvocationStatus.FAILED, 0, undecided(), null, error(), null)));
  }

  @Test
  void undecidedDecisionMustMatchItsStatus() {
    ToolInvocation stored = waiting();
    ToolApproval allowedDecision =
        stored.approval().decide(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED);
    ToolApproval deniedDecision =
        stored.approval().decide(ToolApprovalDecision.DENIED, "d-2", "actor", null, DECIDED);
    // ALLOWED must resume as READY, DENIED must terminate as FAILED
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                stored,
                invocation(ToolInvocationStatus.FAILED, 0, allowedDecision, null, error(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                stored,
                invocation(ToolInvocationStatus.READY, 0, deniedDecision, null, null, null)));
    // the matching status moves stay legal
    ToolInvocation.validateTransition(
        stored, invocation(ToolInvocationStatus.READY, 0, allowedDecision, null, null, null));
    ToolInvocation.validateTransition(
        stored, invocation(ToolInvocationStatus.FAILED, 0, deniedDecision, null, error(), null));
    // WAITING -> CANCELLED keeps the exact undecided approval (Stop)
    ToolInvocation.validateTransition(stored, stored.cancel(error(), T1));
    // WAITING -> FAILED with the exact undecided approval is rejected (no direct fail path)
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                stored,
                invocation(ToolInvocationStatus.FAILED, 0, undecided(), null, error(), null)));
  }

  @Test
  void unknownAdvancesAttemptOnlyFromDispatching() {
    ToolInvocation fromDispatching = dispatching(2, allowed()).unknown(error(), T1);
    assertEquals(ToolInvocationStatus.UNKNOWN, fromDispatching.status());
    assertEquals(3, fromDispatching.attempt());
    ToolInvocation fromRunning = running(2, allowed()).unknown(error(), T1);
    assertEquals(2, fromRunning.attempt());
    assertThrows(IllegalArgumentException.class, () -> ready(0, null).unknown(error(), T1));
    assertThrows(IllegalArgumentException.class, () -> waiting().unknown(error(), T1));
  }

  @Test
  void attachResultEntryLinksOnlyOnTerminal() {
    ToolInvocation attached = succeeded(1).attachResultEntry(99L, T1);
    assertEquals(99L, attached.resultEntryId());
    assertEquals(result(), attached.result());
    assertThrows(IllegalArgumentException.class, () -> ready(0, null).attachResultEntry(1L, T1));
    assertThrows(
        IllegalArgumentException.class,
        () -> succeeded(1).attachResultEntry(99L, T1).attachResultEntry(100L, T2));
  }

  @Test
  void validateTransitionAcceptsExactReplayAndRejectsIdentityAndTimeRegression() {
    ToolInvocation stored = withUpdatedAt(ready(0, null), T1);
    ToolInvocation.validateTransition(stored, stored);
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(stored, withId(stored, 2L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(stored, withRequest(stored)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(stored, withCreatedAt(stored)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(stored, withUpdatedAt(stored, CREATED)));
  }

  @Test
  void validateTransitionRejectsIllegalStatusMovesAttemptDeltasAndApprovalMutation() {
    ToolInvocation stored = ready(0, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(stored, running(1, allowed())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                stored,
                invocation(ToolInvocationStatus.UNKNOWN, 1, allowed(), null, error(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(dispatching(1, allowed()), running(3, allowed())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                dispatching(1, allowed()),
                invocation(ToolInvocationStatus.UNKNOWN, 1, allowed(), null, error(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(running(1, allowed()), succeeded(2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(succeeded(1), failed(1)));
    // approval mutation: undecided with a different request time, decided rewrite
    ToolInvocation undecidedStored = waiting();
    ToolApproval otherRequest = ToolApproval.request(REQUESTED.plusSeconds(5), null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                undecidedStored, withApproval(undecidedStored, otherRequest)));
    ToolInvocation decidedStored = running(1, allowed());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                decidedStored, withApproval(decidedStored, denied())));
    // a decided approval rewritten with the same decision but a different reason is immutable too
    ToolApproval otherAllowed =
        new ToolApproval(
            true, ToolApprovalDecision.ALLOWED, "d-1", "actor", "new reason", REQUESTED, DECIDED);
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(ready(0, allowed()), ready(0, otherAllowed)));
  }

  @Test
  void validateTransitionRejectsTerminalFactMutation() {
    ToolInvocation succeeded = succeeded(1);
    ToolInvocation changedResult =
        new ToolInvocation(
            succeeded.id(),
            succeeded.modelInvocationId(),
            succeeded.assistantEntryId(),
            succeeded.ordinal(),
            succeeded.request(),
            succeeded.status(),
            succeeded.attempt(),
            succeeded.approval(),
            new ToolResult(CALL_ID, List.of(new TextToolContent("different")), false, "{}", false),
            succeeded.error(),
            succeeded.resultEntryId(),
            succeeded.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(succeeded, changedResult));
    ToolInvocation attached = succeeded.attachResultEntry(99L, T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(attached, withResultEntry(attached, null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(attached, attached.attachResultEntry(100L, T2)));
  }

  private static ToolInvocation withId(ToolInvocation source, long id) {
    return new ToolInvocation(
        id,
        source.modelInvocationId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.request(),
        source.status(),
        source.attempt(),
        source.approval(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        source.createdAt(),
        T1);
  }

  private static ToolInvocation withRequest(ToolInvocation source) {
    return new ToolInvocation(
        source.id(),
        source.modelInvocationId(),
        source.assistantEntryId(),
        source.ordinal(),
        request("call-other", "{}"),
        source.status(),
        source.attempt(),
        source.approval(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        source.createdAt(),
        T1);
  }

  private static ToolInvocation withCreatedAt(ToolInvocation source) {
    return new ToolInvocation(
        source.id(),
        source.modelInvocationId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.request(),
        source.status(),
        source.attempt(),
        source.approval(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        T1,
        T1);
  }

  private static ToolInvocation withApproval(ToolInvocation source, ToolApproval approval) {
    return new ToolInvocation(
        source.id(),
        source.modelInvocationId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.request(),
        source.status(),
        source.attempt(),
        approval,
        source.result(),
        source.error(),
        source.resultEntryId(),
        source.createdAt(),
        T1);
  }

  private static ToolInvocation withUpdatedAt(ToolInvocation source, Instant updatedAt) {
    return new ToolInvocation(
        source.id(),
        source.modelInvocationId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.request(),
        source.status(),
        source.attempt(),
        source.approval(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        source.createdAt(),
        updatedAt);
  }

  private static ToolInvocation withResultEntry(ToolInvocation source, Long resultEntryId) {
    return new ToolInvocation(
        source.id(),
        source.modelInvocationId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.request(),
        source.status(),
        source.attempt(),
        source.approval(),
        source.result(),
        source.error(),
        resultEntryId,
        source.createdAt(),
        T1);
  }
}
