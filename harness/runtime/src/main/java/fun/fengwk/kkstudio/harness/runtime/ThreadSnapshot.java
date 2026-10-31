package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.driver.FailureProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntrySlice;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadActivityEvaluator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一致性加锁下的一个 Thread 的不可变投影：durable {@link ThreadState}、当前 Entry 状态、本次读取的 {@link EntrySlice}、已入队
 * Commands，以及 Driver 的中立 generation 诊断与当前 live tool siblings。
 *
 * <p>完整读取的 slice base 为 null，可以重建 {@link EntryPath}。增量读取只携带游标之后的 Entry，不得把增量包装成完整路径。generation 仅在
 * Driver 活动或响应待处理时提供；工具阶段由公共 tool siblings 表示。失败诊断由 {@link GenerationProjection#failures()}
 * 提供，快照不暴露 Driver 私有类型。
 *
 * <p>本记录构造器仅校验 head 与 slice 结构合法性，以及 generation 归属于本 Thread 当前的 open turn。generation 中
 * requestHeadEntryId 与 resultEntryId 的祖先归属由唯一的事务内读取方（{@link HarnessRuntime}）在构造前通过 isAncestor
 * 校验，本记录构造器不对增量切片做成员包含性假设。
 */
public record ThreadSnapshot(
    ThreadState thread,
    EntryCurrentState entryState,
    EntrySlice entrySlice,
    List<ThreadCommand> queuedCommands,
    GenerationProjection generation,
    List<ToolInvocation> toolSiblings,
    ThreadLiveState liveState,
    List<StoppedThreadReceipt> stopReceipts,
    int incompleteChildJoins,
    boolean unconsumedAppliedNotifications,
    List<ExecutionExitFact> unconfirmedExecutions,
    boolean hasIncompleteOwnerChildJoin) {

  public ThreadSnapshot {
    thread = Objects.requireNonNull(thread, "thread");
    entryState = Objects.requireNonNull(entryState, "entryState");
    entrySlice = Objects.requireNonNull(entrySlice, "entrySlice");
    if (!thread.headEntryId().equals(entryState.head().id())) {
      throw new IllegalArgumentException("entry state head must be the thread head");
    }
    if (entrySlice.entries().isEmpty()) {
      if (entrySlice.baseEntryId() == null
          || !entrySlice.baseEntryId().equals(entryState.head().id())) {
        throw new IllegalArgumentException(
            "an empty entry slice must name the current head as base");
      }
    } else if (!entrySlice.entries().getLast().id().equals(entryState.head().id())) {
      throw new IllegalArgumentException("entry slice must end at the current head");
    }
    queuedCommands = List.copyOf(Objects.requireNonNull(queuedCommands, "queuedCommands"));
    toolSiblings = List.copyOf(Objects.requireNonNull(toolSiblings, "toolSiblings"));
    liveState = Objects.requireNonNull(liveState, "liveState");
    if (generation != null) {
      validateGeneration(thread, entryState, generation);
    }
    stopReceipts = List.copyOf(Objects.requireNonNull(stopReceipts, "stopReceipts"));
    unconfirmedExecutions =
        List.copyOf(Objects.requireNonNull(unconfirmedExecutions, "unconfirmedExecutions"));
    if (incompleteChildJoins < 0) {
      throw new IllegalArgumentException("incompleteChildJoins must not be negative");
    }
  }

  /** Driver 投影必须归属于本 Thread 的同一个 open turn。 */
  private static void validateGeneration(
      ThreadState thread, EntryCurrentState state, GenerationProjection generation) {
    Entry turn =
        state
            .openTurnStart()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "driver generation requires an open TURN_START for thread " + thread.id()));
    if (!(turn.payload() instanceof TurnStartPayload start)
        || !thread.id().equals(start.ownerThreadId())
        || !turn.id().equals(generation.turnStartEntryId())) {
      throw new IllegalStateException(
          "driver generation does not belong to thread " + thread.id() + " turn " + turn.id());
    }
  }

  /** 仅完整读取可重建合法路径；增量 slice 不能冒充 {@link EntryPath}。 */
  public EntryPath requireFullPath() {
    if (entrySlice.baseEntryId() != null) {
      throw new IllegalStateException("incremental thread snapshot has no full entry path");
    }
    return new EntryPath(entrySlice.entries());
  }

  /** 仅 UNCONFIRMED 事实阻塞收敛；ABANDONED 已由用户显式解除阻塞。 */
  public boolean hasUnconfirmedExecution() {
    return !unconfirmedExecutions.isEmpty();
  }

  /** 当前 generation 尚未物化的 failed attempt 投影。 */
  public List<FailureProjection> generationFailures() {
    return generation == null ? List.of() : generation.failures();
  }

  /** 共享的运行时三态评估逻辑，供快照与树节点统一使用。 */
  static ThreadRuntimeStatus evaluateRuntimeStatus(
      ThreadExecutionControl control,
      ThreadLiveState liveState,
      EntryCurrentState entryState,
      UUID threadId,
      List<ThreadCommand> queuedCommands,
      boolean unconsumedAppliedNotifications,
      int incompleteChildJoins,
      boolean hasIncompleteOwnerChildJoin,
      boolean hasUnconfirmedExecution) {
    boolean hasOpenCompaction =
        entryState
            .openTurnStart()
            .map(
                entry ->
                    entry.payload() instanceof TurnStartPayload start
                        && start.reason() == TurnStartReason.COMPACTION
                        && threadId.equals(start.ownerThreadId()))
            .orElse(false);
    boolean hasCompactionObligation = hasOpenCompaction || hasIncompleteOwnerChildJoin;
    boolean hasInputDemand =
        ThreadInputDemand.hasQueuedDemand(queuedCommands) || unconsumedAppliedNotifications;
    return ThreadActivityEvaluator.evaluate(
        control,
        liveState.isLive(),
        hasCompactionObligation,
        hasInputDemand,
        incompleteChildJoins,
        hasUnconfirmedExecution);
  }

  /** 共享的 outcome 评估逻辑：只取决于运行状态是否为 IDLE 以及实际 head 是否为 TURN_END。 */
  static TurnEndOutcome evaluateOutcome(ThreadRuntimeStatus status, EntryCurrentState entryState) {
    if (status != ThreadRuntimeStatus.IDLE) {
      return null;
    }
    if (entryState.head().payload() instanceof TurnEndPayload end) {
      return end.outcome();
    }
    return null;
  }

  /** 统一将纯上下文分类 {@link ThreadContext} 映射为公共 {@link ThreadLiveState}。 */
  static ThreadLiveState liveStateOf(ThreadContext context) {
    return switch (context) {
      case ThreadContext.IdleOrHistorical ignored -> ThreadLiveState.IDLE_OR_HISTORICAL;
      case ThreadContext.ContinuationDue ignored -> ThreadLiveState.CONTINUATION_DUE;
      case ThreadContext.ModelActive ignored -> ThreadLiveState.DRIVER_ACTIVE;
      case ThreadContext.ModelTerminalPending ignored -> ThreadLiveState.DRIVER_RESPONSE_PENDING;
      case ThreadContext.ToolActive ignored -> ThreadLiveState.TOOL_ACTIVE;
      case ThreadContext.ToolTerminalPending ignored -> ThreadLiveState.TOOL_TERMINAL_PENDING;
    };
  }

  /**
   * 运行时状态投影为三态（{@link ThreadRuntimeStatus#RUNNING} / {@link ThreadRuntimeStatus#WAITING} / {@link
   * ThreadRuntimeStatus#IDLE}），与持久执行控制 {@link ThreadExecutionControl} 正交。只使用当前 Entry 状态。
   */
  public ThreadRuntimeStatus runtimeStatus() {
    return evaluateRuntimeStatus(
        thread.executionControl(),
        liveState,
        entryState,
        thread.id(),
        queuedCommands,
        unconsumedAppliedNotifications,
        incompleteChildJoins,
        hasIncompleteOwnerChildJoin,
        hasUnconfirmedExecution());
  }

  /** 本 Thread 的统一执行事实投影：由排队命令、当前 live state 与本地 Tool terminal 状态唯一决定，不递归查询后代、不接触 Store。 */
  public ThreadExecutionFacts executionFacts() {
    boolean hasWork =
        !queuedCommands.isEmpty()
            || liveState == ThreadLiveState.DRIVER_ACTIVE
            || liveState == ThreadLiveState.CONTINUATION_DUE;
    boolean waitingForHuman = false;
    boolean hasUnknownToolOutcome = false;
    for (ToolInvocation sibling : toolSiblings) {
      ToolInvocationStatus status = sibling.status();
      hasWork |= status == ToolInvocationStatus.READY || status == ToolInvocationStatus.RUNNING;
      waitingForHuman |=
          status == ToolInvocationStatus.WAITING_APPROVAL
              || status == ToolInvocationStatus.WAITING_INPUT;
      hasUnknownToolOutcome |= status == ToolInvocationStatus.UNKNOWN;
    }
    return new ThreadExecutionFacts(hasWork, waitingForHuman, hasUnknownToolOutcome);
  }

  /**
   * 仅当运行时状态为 {@link ThreadRuntimeStatus#IDLE} 且实际 head 是 {@code TURN_END} 时返回其 outcome；其它状态与 head
   * 一律为 null。
   */
  public TurnEndOutcome outcome() {
    return evaluateOutcome(runtimeStatus(), entryState);
  }
}
