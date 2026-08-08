package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationTestData.CALL_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationTestData.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;

/** ToolInvocation 纯 transition 方法以及共享的 transition 校验。 */
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
    // 完全相同的 decision payload 重放是 idempotent 的
    ToolInvocation replay =
        decided.decideApproval(ToolApprovalDecision.ALLOWED, "d-1", "actor", null, DECIDED, T2);
    assertEquals(decided.approval(), replay.approval());
    assertEquals(ToolInvocationStatus.READY, replay.status());
    // 相同 decisionId 但不同 payload 视为冲突
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decided.decideApproval(
                ToolApprovalDecision.ALLOWED, "d-1", "other-actor", null, DECIDED, T2));
    // 已存在但不同的 decision 视为冲突
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decided.decideApproval(ToolApprovalDecision.DENIED, "d-9", "actor", null, DECIDED, T2));
    // DENIED 终态下的 replay 也是 idempotent 的：每个 decision 事实都保持冻结，只有
    // invocation 的 updatedAt 前进到 replay 时间
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
    // 没有 approval 时 decide 被拒绝
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
  void successPersistsEffectsAtomicallyAndKeepsThemTerminalImmutable() {
    ToolEffectBatch effects =
        new ToolEffectBatch(
            List.of(new CustomEntryPayload("goal", "state", 1, "{\"objective\":\"ship\"}")));
    ToolInvocation succeeded = running(1, allowed()).succeed(result(), effects, T1);
    assertEquals(effects, succeeded.effects());

    ToolInvocation attached = succeeded.attachResultEntry(99L, T2);
    assertEquals(effects, attached.effects());
    assertEquals(99L, attached.resultEntryId());

    ToolEffectBatch changed =
        new ToolEffectBatch(
            List.of(new CustomEntryPayload("goal", "state", 1, "{\"objective\":\"other\"}")));
    ToolInvocation rewritten =
        new ToolInvocation(
            succeeded.id(),
            succeeded.modelInvocationId(),
            succeeded.assistantEntryId(),
            succeeded.ordinal(),
            succeeded.request(),
            succeeded.status(),
            succeeded.attempt(),
            succeeded.approval(),
            succeeded.result(),
            changed,
            succeeded.error(),
            succeeded.resultEntryId(),
            succeeded.createdAt(),
            T2);
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(succeeded, rewritten));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                2L,
                1L,
                1L,
                0,
                request("bash", "{}"),
                ToolInvocationStatus.FAILED,
                0,
                null,
                null,
                effects,
                error(),
                null,
                CREATED,
                CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocation(
                3L,
                1L,
                1L,
                0,
                request("bash", "{}"),
                ToolInvocationStatus.RUNNING,
                1,
                allowed(),
                null,
                effects,
                null,
                null,
                CREATED,
                CREATED));
  }

  @Test
  void failTerminatesFromReadyAndRunningKeepingAttempt() {
    assertEquals(ToolInvocationStatus.FAILED, ready(0, null).fail(error(), T1).status());
    assertEquals(0, ready(0, null).fail(error(), T1).attempt());
    assertEquals(2, running(2, allowed()).fail(error(), T1).attempt());
    assertThrows(IllegalArgumentException.class, () -> succeeded(1).fail(error(), T1));
    // DISPATCHING 只能通过 rejectDispatch 失败，不能调用 fail()
    assertThrows(IllegalArgumentException.class, () -> dispatching(1, allowed()).fail(error(), T1));
    // WAITING_APPROVAL 不能直接 fail：必须先把未决 approval decide 为（DENIED ->
    // FAILED）或 stop 为 CANCELLED
    assertThrows(IllegalArgumentException.class, () -> waiting().fail(error(), T1));
  }

  @Test
  void cancelTerminatesFromWaitingReadyAndRunningButNotDispatching() {
    assertEquals(ToolInvocationStatus.CANCELLED, waiting().cancel(error(), T1).status());
    assertEquals(0, waiting().cancel(error(), T1).attempt());
    assertTrue(waiting().cancel(error(), T1).approval().isUndecided());
    assertEquals(0, ready(0, null).cancel(error(), T1).attempt());
    assertEquals(2, running(2, allowed()).cancel(error(), T1).attempt());
    // DISPATCHING 是执行可能已经启动的窗口：UNKNOWN 是唯一诚实的终态，
    // CANCELLED 被拒绝
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
    // 从 null 的存储 approval 直接注入已决定的 approval 被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(stored, ready(0, allowed())));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocation.validateTransition(stored, ready(0, denied())));
    // READY 携带 not-required 转为 READY，以及 READY 携带 undecided 转为 WAITING_APPROVAL 都是合法的
    ToolInvocation.validateTransition(stored, stored.markApprovalNotRequired(T1));
    ToolInvocation.validateTransition(stored, stored.requestApproval("why", T1));
    // 在其他任何边上引入 approval 均被拒绝
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
    // ALLOWED 必须恢复为 READY，DENIED 必须以 FAILED 终止
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
    // 匹配的 status 迁移保持合法
    ToolInvocation.validateTransition(
        stored, invocation(ToolInvocationStatus.READY, 0, allowedDecision, null, null, null));
    ToolInvocation.validateTransition(
        stored, invocation(ToolInvocationStatus.FAILED, 0, deniedDecision, null, error(), null));
    // WAITING -> CANCELLED 保留完全相同的未决 approval（Stop）
    ToolInvocation.validateTransition(stored, stored.cancel(error(), T1));
    // WAITING 携带完全相同的未决 approval 直接转为 FAILED 被拒绝（无直接 fail 路径）
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
    // approval 修改：未决 approval 改为不同的请求时间、已决定 approval 重写
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
    // 已决定 approval 即使 decision 相同但 reason 不同也视为不可变
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
