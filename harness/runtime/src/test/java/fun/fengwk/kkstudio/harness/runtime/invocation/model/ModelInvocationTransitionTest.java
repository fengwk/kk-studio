package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.checkpoint;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.error;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.request;
import static fun.fengwk.kkstudio.harness.runtime.invocation.model.InvocationTestData.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ModelInvocation 纯 transition 方法以及共享的 transition 校验。 */
class ModelInvocationTransitionTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = CREATED.plusSeconds(1);
  private static final Instant T2 = CREATED.plusSeconds(2);
  private static final Instant T3 = CREATED.plusSeconds(3);

  private static ModelInvocation ready(int attempt) {
    return invocation(ModelInvocationStatus.READY, attempt, null, null, null, null);
  }

  private static ModelInvocation dispatching(int attempt) {
    return invocation(ModelInvocationStatus.DISPATCHING, attempt, null, null, null, null);
  }

  private static ModelInvocation running(int attempt, StreamCheckpoint checkpoint) {
    return invocation(ModelInvocationStatus.RUNNING, attempt, checkpoint, null, null, null);
  }

  private static ModelInvocation succeeded(int attempt) {
    return invocation(ModelInvocationStatus.SUCCEEDED, attempt, null, response(), null, null);
  }

  private static ModelInvocation failed(int attempt) {
    return invocation(ModelInvocationStatus.FAILED, attempt, null, null, error(), null);
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
        CREATED);
  }

  @Test
  void beginDispatchMovesReadyToDispatchingKeepingAttempt() {
    ModelInvocation next = ready(2).beginDispatch(T1);
    assertEquals(ModelInvocationStatus.DISPATCHING, next.status());
    assertEquals(2, next.attempt());
    assertNull(next.streamCheckpoint());
    assertNull(next.result());
    assertNull(next.error());
    assertNull(next.resultEntryId());
    assertEquals(T1, next.updatedAt());
    assertThrows(
        IllegalArgumentException.class, () -> ready(0).beginDispatch(T1).beginDispatch(T2));
    assertThrows(IllegalArgumentException.class, () -> running(1, null).beginDispatch(T1));
    assertThrows(IllegalArgumentException.class, () -> succeeded(1).beginDispatch(T1));
  }

  @Test
  void rejectDispatchFailsBeforeStartWithoutAdvancingAttempt() {
    ModelInvocation next = dispatching(0).rejectDispatch(error(), T1);
    assertEquals(ModelInvocationStatus.FAILED, next.status());
    assertEquals(0, next.attempt());
    assertEquals(error(), next.error());
    // 在 retry dispatch 上，确切的预启动拒绝也合法
    assertEquals(2, dispatching(2).rejectDispatch(error(), T1).attempt());
    assertThrows(IllegalArgumentException.class, () -> ready(0).rejectDispatch(error(), T1));
  }

  @Test
  void dispatchBusyReturnsToReadyKeepingAttempt() {
    ModelInvocation next = dispatching(2).dispatchBusy(T1);
    assertEquals(ModelInvocationStatus.READY, next.status());
    assertEquals(2, next.attempt());
    assertNull(next.streamCheckpoint());
    assertThrows(IllegalArgumentException.class, () -> ready(0).dispatchBusy(T1));
    assertThrows(IllegalArgumentException.class, () -> running(1, null).dispatchBusy(T1));
  }

  @Test
  void markRunningConfirmsTheStartAndAdvancesAttemptByOne() {
    ModelInvocation next = dispatching(2).markRunning(T1);
    assertEquals(ModelInvocationStatus.RUNNING, next.status());
    assertEquals(3, next.attempt());
    assertNull(next.streamCheckpoint());
    assertThrows(IllegalArgumentException.class, () -> ready(0).markRunning(T1));
    assertThrows(
        IllegalArgumentException.class,
        () -> dispatching(0).beginDispatch(T1).markRunning(T2).markRunning(T3));
  }

  @Test
  void checkpointIsMonotonicPerAttempt() {
    StreamCheckpoint first = checkpoint(1);
    ModelInvocation running = running(1, null);
    assertEquals(first, running.checkpoint(first, T1).streamCheckpoint());
    // 更大但仍是严格前缀的 sequence
    StreamCheckpoint grown = new StreamCheckpoint(1, 2L, "partial-extended", null);
    ModelInvocation withCheckpoint = running.checkpoint(first, T1).checkpoint(grown, T2);
    assertEquals(grown, withCheckpoint.streamCheckpoint());
    // 相同 sequence 要求完全 idempotent
    assertEquals(grown, withCheckpoint.checkpoint(grown, T3).streamCheckpoint());
    // 更小 sequence 或分叉均被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () -> withCheckpoint.checkpoint(new StreamCheckpoint(1, 1L, "partial", null), T3));
    assertThrows(
        IllegalArgumentException.class,
        () -> withCheckpoint.checkpoint(new StreamCheckpoint(1, 2L, "different", null), T3));
    assertThrows(
        IllegalArgumentException.class,
        () -> withCheckpoint.checkpoint(new StreamCheckpoint(1, 3L, "partial-other", null), T3));
    // checkpoint 保持在同一 attempt 上
    assertThrows(
        IllegalArgumentException.class,
        () -> withCheckpoint.checkpoint(new StreamCheckpoint(2, 0L, "other attempt", null), T3));
    // 仍在 running 时不能清除 checkpoint（transition 校验会拒绝）
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(withCheckpoint, running(1, null)));
    assertThrows(IllegalArgumentException.class, () -> ready(0).checkpoint(first, T1));
  }

  @Test
  void succeedCompletesRunningKeepingAttemptAndCheckpoint() {
    ModelInvocation withCheckpoint = running(1, checkpoint(1)).checkpoint(checkpoint(1), T1);
    ModelInvocation next = withCheckpoint.succeed(response(), T2);
    assertEquals(ModelInvocationStatus.SUCCEEDED, next.status());
    assertEquals(1, next.attempt());
    assertEquals(response(), next.result());
    assertEquals(checkpoint(1), next.streamCheckpoint());
    assertNull(next.error());
    assertThrows(IllegalArgumentException.class, () -> ready(0).succeed(response(), T1));
    assertThrows(IllegalArgumentException.class, () -> dispatching(0).succeed(response(), T1));
  }

  @Test
  void transitionMethodsClampWallClockRollbackToCurrentUpdatedAt() {
    ModelInvocation stored = withUpdatedAt(running(1, null), T2);

    // Provider terminal callback 可携带早于 durable 行的 wall-clock 样本，但不能让 updatedAt 回退。
    ModelInvocation next = stored.succeed(response(), T1);
    assertEquals(ModelInvocationStatus.SUCCEEDED, next.status());
    assertEquals(T2, next.updatedAt());
  }

  @Test
  void failTerminatesFromReadyAndRunningKeepingAttempt() {
    assertEquals(ModelInvocationStatus.FAILED, ready(0).fail(error(), T1).status());
    assertEquals(0, ready(0).fail(error(), T1).attempt());
    assertEquals(2, running(2, null).fail(error(), T1).attempt());
    // DISPATCHING 只能通过 rejectDispatch 失败，不能调用 fail()
    assertThrows(IllegalArgumentException.class, () -> dispatching(1).fail(error(), T1));
    assertThrows(IllegalArgumentException.class, () -> succeeded(1).fail(error(), T1));
    // 终态 FAILED 行拒绝用不同 error 重新失败（终态事实不可变）
    assertThrows(
        IllegalArgumentException.class,
        () ->
            failed(1)
                .fail(new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "other"), T2));
  }

  @Test
  void cancelTerminatesAdvancingAttemptOnlyFromDispatching() {
    assertEquals(ModelInvocationStatus.CANCELLED, ready(0).cancel(error(), T1).status());
    assertEquals(0, ready(0).cancel(error(), T1).attempt());
    // DISPATCHING 是调用可能已经启动的 Stop 窗口：attempt + 1
    assertEquals(2, dispatching(1).cancel(error(), T1).attempt());
    assertEquals(2, running(2, null).cancel(error(), T1).attempt());
    assertThrows(IllegalArgumentException.class, () -> succeeded(1).cancel(error(), T1));
  }

  @Test
  void retryReadyReturnsToReadyKeepingAttemptAndDroppingCheckpoint() {
    ModelInvocation withCheckpoint = running(1, checkpoint(1));
    ModelInvocation next = withCheckpoint.retryReady(T1);
    assertEquals(ModelInvocationStatus.READY, next.status());
    assertEquals(1, next.attempt());
    assertNull(next.streamCheckpoint());
    assertNull(next.result());
    assertNull(next.error());
    assertThrows(IllegalArgumentException.class, () -> ready(0).retryReady(T1));
    assertThrows(IllegalArgumentException.class, () -> dispatching(0).retryReady(T1));
    assertThrows(IllegalArgumentException.class, () -> succeeded(1).retryReady(T1));
    assertThrows(IllegalArgumentException.class, () -> failed(1).retryReady(T1));
  }

  @Test
  void checkpointMayOnlyBeIntroducedOrGrownWhileRunning() {
    // 直接向终态 transition 注入 checkpoint 被拒绝
    ModelInvocation runningNoCheckpoint = running(1, null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                runningNoCheckpoint,
                invocation(
                    ModelInvocationStatus.SUCCEEDED, 1, checkpoint(1), response(), null, null)));
    // 进入终态时增长 checkpoint 被拒绝
    ModelInvocation runningWithCheckpoint = running(1, checkpoint(1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                runningWithCheckpoint,
                invocation(
                    ModelInvocationStatus.SUCCEEDED,
                    1,
                    new StreamCheckpoint(1, 1L, "partial+", null),
                    response(),
                    null,
                    null)));
    // 终态不能从 null 引入 checkpoint
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                succeeded(1),
                invocation(
                    ModelInvocationStatus.SUCCEEDED, 1, checkpoint(1), response(), null, null)));
    // 终态不能增长或分叉其已存储的 checkpoint
    ModelInvocation terminalWithCheckpoint =
        invocation(ModelInvocationStatus.SUCCEEDED, 1, checkpoint(1), response(), null, null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                terminalWithCheckpoint,
                invocation(
                    ModelInvocationStatus.SUCCEEDED,
                    1,
                    new StreamCheckpoint(1, 1L, "partial+", null),
                    response(),
                    null,
                    null)));
    // DISPATCHING -> RUNNING 是已确认启动而非流式：不能注入 checkpoint
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                dispatching(0),
                invocation(ModelInvocationStatus.RUNNING, 1, checkpoint(1), null, null, null)));
    // RUNNING -> READY（retry）丢弃 checkpoint，RUNNING -> RUNNING 仍允许增长
    ModelInvocation.validateTransition(runningWithCheckpoint, runningWithCheckpoint.retryReady(T1));
    ModelInvocation.validateTransition(
        runningWithCheckpoint, runningWithCheckpoint.checkpoint(checkpoint(1), T1));
  }

  @Test
  void unknownAdvancesAttemptOnlyFromDispatching() {
    ModelInvocation fromDispatching = dispatching(2).unknown(error(), T1);
    assertEquals(ModelInvocationStatus.UNKNOWN, fromDispatching.status());
    assertEquals(3, fromDispatching.attempt());
    ModelInvocation fromRunning = running(2, null).unknown(error(), T1);
    assertEquals(2, fromRunning.attempt());
    assertThrows(IllegalArgumentException.class, () -> ready(0).unknown(error(), T1));
    assertThrows(IllegalArgumentException.class, () -> failed(1).unknown(error(), T1));
  }

  @Test
  void attachResultEntryLinksOnlyOnTerminalAndClearsTheCheckpoint() {
    ModelInvocation attached = succeeded(1).attachResultEntry(99L, T1);
    assertEquals(99L, attached.resultEntryId());
    // 从携带 checkpoint 的终态 attach 时会清掉它并保留其他全部事实
    ModelInvocation terminalWithCheckpoint =
        new ModelInvocation(
            1L,
            1L,
            1L,
            1L,
            request(),
            ModelInvocationStatus.SUCCEEDED,
            1,
            checkpoint(1),
            response(),
            null,
            null,
            CREATED,
            CREATED);
    ModelInvocation attachedWithCheckpoint = terminalWithCheckpoint.attachResultEntry(99L, T2);
    assertEquals(99L, attachedWithCheckpoint.resultEntryId());
    assertNull(attachedWithCheckpoint.streamCheckpoint());
    assertEquals(response(), attachedWithCheckpoint.result());
    assertThrows(IllegalArgumentException.class, () -> ready(0).attachResultEntry(1L, T1));
    assertThrows(
        IllegalArgumentException.class,
        () -> succeeded(1).attachResultEntry(99L, T1).attachResultEntry(100L, T2));
    assertThrows(IllegalArgumentException.class, () -> succeeded(1).attachResultEntry(0L, T1));
  }

  @Test
  void validateTransitionAcceptsExactReplayAndUpdatedAtTouches() {
    ModelInvocation stored = ready(0);
    // 完全一致的 replay 被接受
    ModelInvocation replay =
        new ModelInvocation(
            stored.id(),
            stored.threadId(),
            stored.turnStartEntryId(),
            stored.basisHeadEntryId(),
            stored.request(),
            stored.status(),
            stored.attempt(),
            stored.streamCheckpoint(),
            stored.result(),
            stored.error(),
            stored.resultEntryId(),
            stored.createdAt(),
            stored.updatedAt());
    ModelInvocation.validateTransition(stored, replay);
    // 终态行仍可更新 updatedAt 而不改变终态事实
    ModelInvocation terminal = succeeded(1);
    ModelInvocation touched =
        new ModelInvocation(
            terminal.id(),
            terminal.threadId(),
            terminal.turnStartEntryId(),
            terminal.basisHeadEntryId(),
            terminal.request(),
            terminal.status(),
            terminal.attempt(),
            terminal.streamCheckpoint(),
            terminal.result(),
            terminal.error(),
            terminal.resultEntryId(),
            terminal.createdAt(),
            T1);
    ModelInvocation.validateTransition(terminal, touched);
  }

  @Test
  void validateTransitionRejectsIdentityAndTimeRegression() {
    ModelInvocation stored = withUpdatedAt(ready(0), T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(stored, withId(stored, 2L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(stored, withThread(stored, 2L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(stored, withTurnStart(stored, 2L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(stored, withBasis(stored, 2L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(stored, withRequest(stored)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(stored, withCreatedAt(stored)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(stored, withUpdatedAt(stored, CREATED)));
  }

  @Test
  void validateTransitionRejectsIllegalStatusMovesAndAttemptDeltas() {
    ModelInvocation stored = ready(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(stored, running(1, null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                stored, invocation(ModelInvocationStatus.UNKNOWN, 1, null, null, error(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                stored, invocation(ModelInvocationStatus.DISPATCHING, 1, null, null, null, null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(running(1, null), dispatching(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(dispatching(0), succeeded(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(succeeded(1), failed(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(failed(1), ready(1)));
    // attempt 不可回退，仅在已确认启动时前进
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(ready(1), ready(0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                dispatching(1),
                invocation(ModelInvocationStatus.RUNNING, 3, null, null, null, null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                dispatching(1),
                invocation(ModelInvocationStatus.UNKNOWN, 1, null, null, error(), null)));
    // DISPATCHING stop 窗口必须将 attempt 恰好前进 1，绝不能保持不变
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                dispatching(1),
                invocation(ModelInvocationStatus.CANCELLED, 1, null, null, error(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(running(1, null), succeeded(2)));
  }

  @Test
  void validateTransitionRejectsTerminalFactMutation() {
    ModelInvocation succeeded = succeeded(1);
    ModelInvocation failed = failed(1);
    // 在事实相同的情况下重写 status
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                succeeded, invocation(ModelInvocationStatus.FAILED, 1, null, null, error(), null)));
    // result / error 修改
    ModelInvocation changedResult =
        new ModelInvocation(
            succeeded.id(),
            succeeded.threadId(),
            succeeded.turnStartEntryId(),
            succeeded.basisHeadEntryId(),
            succeeded.request(),
            succeeded.status(),
            succeeded.attempt(),
            succeeded.streamCheckpoint(),
            differentResponse(),
            succeeded.error(),
            succeeded.resultEntryId(),
            succeeded.createdAt(),
            T1);
    ModelInvocation changedError =
        new ModelInvocation(
            failed.id(),
            failed.threadId(),
            failed.turnStartEntryId(),
            failed.basisHeadEntryId(),
            failed.request(),
            failed.status(),
            failed.attempt(),
            failed.streamCheckpoint(),
            failed.result(),
            new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "other"),
            failed.resultEntryId(),
            failed.createdAt(),
            T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(succeeded, changedResult));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(failed, changedError));
    // 终态行的 attempt 修改
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                failed, invocation(ModelInvocationStatus.FAILED, 2, null, null, error(), null)));
    // resultEntryId 只能从 null 开始 attach；正数值冻结
    ModelInvocation attached = succeeded(1).attachResultEntry(99L, T1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(attached, attached.attachResultEntry(100L, T2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(attached, withResultEntry(attached, null)));
  }

  @Test
  void validateTransitionRejectsInvalidCheckpointChanges() {
    ModelInvocation running = running(1, checkpoint(1));
    // 仍在 running 时清除
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelInvocation.validateTransition(running, running(1, null)));
    // 相同 sequence 但不同内容
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                running, running(1, new StreamCheckpoint(1, 0L, "other", null))));
    // 回退 sequence
    ModelInvocation grown = running.checkpoint(new StreamCheckpoint(1, 1L, "partial+", null), T1);
    assertThrows(
        IllegalArgumentException.class, () -> ModelInvocation.validateTransition(grown, running));
    // 非前缀式增长
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelInvocation.validateTransition(
                grown, running(1, new StreamCheckpoint(1, 2L, "other", null))));
    // 在终态清除是唯一允许的清除
    ModelInvocation.validateTransition(running, running.fail(error(), T1));
  }

  @Test
  void rejectDispatchAcceptsAnyDefinitePreStartError() {
    ModelInvocation next =
        dispatching(0)
            .rejectDispatch(
                new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, "quota"), T1);
    assertEquals(ModelInvocationStatus.FAILED, next.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, next.error().kind());
  }

  /** 与 {@link InvocationTestData#response()} 明显不同的 ProviderResponse。 */
  private static ProviderResponse differentResponse() {
    return new ProviderResponse(
        "different",
        null,
        List.of(),
        ProviderStopReason.COMPLETED,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        "req-2",
        null,
        null);
  }

  private static ModelInvocation withId(ModelInvocation source, long id) {
    return new ModelInvocation(
        id,
        source.threadId(),
        source.turnStartEntryId(),
        source.basisHeadEntryId(),
        source.request(),
        source.status(),
        source.attempt(),
        source.streamCheckpoint(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        source.createdAt(),
        source.updatedAt());
  }

  private static ModelInvocation withThread(ModelInvocation source, long threadId) {
    return new ModelInvocation(
        source.id(),
        threadId,
        source.turnStartEntryId(),
        source.basisHeadEntryId(),
        source.request(),
        source.status(),
        source.attempt(),
        source.streamCheckpoint(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        source.createdAt(),
        source.updatedAt());
  }

  private static ModelInvocation withTurnStart(ModelInvocation source, long turnStartEntryId) {
    return new ModelInvocation(
        source.id(),
        source.threadId(),
        turnStartEntryId,
        source.basisHeadEntryId(),
        source.request(),
        source.status(),
        source.attempt(),
        source.streamCheckpoint(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        source.createdAt(),
        source.updatedAt());
  }

  private static ModelInvocation withBasis(ModelInvocation source, long basisHeadEntryId) {
    return new ModelInvocation(
        source.id(),
        source.threadId(),
        source.turnStartEntryId(),
        basisHeadEntryId,
        source.request(),
        source.status(),
        source.attempt(),
        source.streamCheckpoint(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        source.createdAt(),
        source.updatedAt());
  }

  private static ModelInvocation withRequest(ModelInvocation source) {
    return new ModelInvocation(
        source.id(),
        source.threadId(),
        source.turnStartEntryId(),
        source.basisHeadEntryId(),
        request(List.of(), false),
        source.status(),
        source.attempt(),
        source.streamCheckpoint(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        source.createdAt(),
        source.updatedAt());
  }

  private static ModelInvocation withCreatedAt(ModelInvocation source) {
    return new ModelInvocation(
        source.id(),
        source.threadId(),
        source.turnStartEntryId(),
        source.basisHeadEntryId(),
        source.request(),
        source.status(),
        source.attempt(),
        source.streamCheckpoint(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        T1,
        T1);
  }

  private static ModelInvocation withUpdatedAt(ModelInvocation source, Instant updatedAt) {
    return new ModelInvocation(
        source.id(),
        source.threadId(),
        source.turnStartEntryId(),
        source.basisHeadEntryId(),
        source.request(),
        source.status(),
        source.attempt(),
        source.streamCheckpoint(),
        source.result(),
        source.error(),
        source.resultEntryId(),
        source.createdAt(),
        updatedAt);
  }

  private static ModelInvocation withResultEntry(ModelInvocation source, Long resultEntryId) {
    return new ModelInvocation(
        source.id(),
        source.threadId(),
        source.turnStartEntryId(),
        source.basisHeadEntryId(),
        source.request(),
        source.status(),
        source.attempt(),
        source.streamCheckpoint(),
        source.result(),
        source.error(),
        resultEntryId,
        source.createdAt(),
        source.updatedAt());
  }
}
