package fun.fengwk.kkstudio.harness.api.thread;

import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.generation.FailureProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntrySlice;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;

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
 * requestHeadEntryId 与 resultEntryId 的祖先归属由唯一的事务内读取方（HarnessRuntime 的快照读取路径）在构造前通过 isAncestor
 * 校验，本记录构造器不对增量切片做成员包含性假设。
 *
 * <p>记录只携带公共值类型：{@link #runtimeStatus()} 与 {@link #outcome()} 是纯值投影，状态机内部的上下文分类不外泄到本边界。
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

  /**
   * 运行时状态四态评估的共享纯值算法，供快照投影与执行树节点投影统一使用。
   *
   * <p>输入全部是调用方已读取的客观事实；本方法不接触 Store、事务或 Runtime 内部类型。状态机内部的上下文分类必须先由 Runtime 判定，并投影为公共 {@link
   * ThreadLiveState}。
   */
  public static ThreadRuntimeStatus evaluateRuntimeStatus(
      ThreadExecutionControl control,
      ThreadLiveState liveState,
      EntryCurrentState entryState,
      UUID threadId,
      List<ThreadCommand> queuedCommands,
      boolean unconsumedAppliedNotifications,
      int incompleteChildJoins,
      boolean hasIncompleteOwnerChildJoin,
      boolean hasUnconfirmedExecution) {
    Objects.requireNonNull(control, "control");
    if (incompleteChildJoins < 0) {
      throw new IllegalArgumentException("incompleteChildJoins must not be negative");
    }
    // 只有真实活跃 Driver/Tool 与终态物化优先于残留退出；续作仍属于下一层本地义务。
    if (hasActiveExecutionFlow(liveState)) {
      return ThreadRuntimeStatus.RUNNING;
    }
    // 已登记但尚未确认退出的具体代次阻塞收敛/待启动动作；不因 RUNNABLE/STOPPED 改变，也不冒充 IDLE。
    if (hasUnconfirmedExecution) {
      return ThreadRuntimeStatus.EXIT_PENDING;
    }
    boolean hasCompactionObligation =
        hasOpenOwnerCompaction(entryState, threadId) || hasIncompleteOwnerChildJoin;
    boolean hasInputDemand = hasQueuedDemand(queuedCommands) || unconsumedAppliedNotifications;
    // 续作、自身 open owner compaction 与尚未冻结的 owner 结算子 Join 属于本地未结束流程。
    if (liveState == ThreadLiveState.CONTINUATION_DUE || hasCompactionObligation) {
      return ThreadRuntimeStatus.RUNNING;
    }
    // RUNNABLE 下输入需求表达为待启动 RUNNING；STOPPED 保留通知但不自启。
    if (hasInputDemand && control == ThreadExecutionControl.RUNNABLE) {
      return ThreadRuntimeStatus.RUNNING;
    }
    // 本地已让出执行但仍有未完成的直接子 Join。
    if (incompleteChildJoins > 0) {
      return ThreadRuntimeStatus.WAITING;
    }
    return ThreadRuntimeStatus.IDLE;
  }

  /** 活跃 Driver/Tool 或终态物化必须优先于残留退出，避免正常执行被误报为退出待确认。 */
  private static boolean hasActiveExecutionFlow(ThreadLiveState liveState) {
    return switch (liveState) {
      case DRIVER_ACTIVE, DRIVER_RESPONSE_PENDING, TOOL_ACTIVE, TOOL_TERMINAL_PENDING -> true;
      case IDLE_OR_HISTORICAL, CONTINUATION_DUE -> false;
    };
  }

  /** 当前 open Turn 是否是调用方 Thread 自己发起的 COMPACTION。 */
  private static boolean hasOpenOwnerCompaction(EntryCurrentState entryState, UUID threadId) {
    return entryState
        .openTurnStart()
        .map(
            entry ->
                entry.payload() instanceof TurnStartPayload start
                    && start.reason() == TurnStartReason.COMPACTION
                    && threadId.equals(start.ownerThreadId()))
        .orElse(false);
  }

  /** queued 快照中是否存在需要驱动 turn 的输入：消息与系统通知构成需求，SET_* 不构成。 */
  private static boolean hasQueuedDemand(List<ThreadCommand> queued) {
    for (ThreadCommand command : queued) {
      if (command.type().isMessage() || command.type().isNotification()) {
        return true;
      }
    }
    return false;
  }

  /** outcome 评估的共享纯值算法：只取决于运行状态是否为 IDLE 以及实际 head 是否为 TURN_END。 */
  public static TurnEndOutcome evaluateOutcome(
      ThreadRuntimeStatus status, EntryCurrentState entryState) {
    if (status != ThreadRuntimeStatus.IDLE) {
      return null;
    }
    if (entryState.head().payload() instanceof TurnEndPayload end) {
      return end.outcome();
    }
    return null;
  }

  /**
   * 运行时状态投影为四态（{@link ThreadRuntimeStatus#RUNNING} / {@link ThreadRuntimeStatus#EXIT_PENDING} /
   * {@link ThreadRuntimeStatus#WAITING} / {@link ThreadRuntimeStatus#IDLE}），与持久执行控制 {@link
   * ThreadExecutionControl} 正交。只使用当前 Entry 状态。
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
