package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.driver.FailureProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadActivityEvaluator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.Objects;

/**
 * 一致性加锁下的一个 Thread 的不可变投影：durable {@link ThreadState}、当前 root-to-head {@link EntryPath}、已入队
 * Commands，以及 Driver 的中立 generation 诊断与当前 live tool siblings。
 *
 * <p>generation 仅在 Driver 活动或响应待处理时提供；工具阶段由公共 tool siblings 表示。失败诊断由 {@link
 * GenerationProjection#failures()} 提供，快照不暴露 Driver 私有类型。
 */
public record ThreadSnapshot(
    ThreadState thread,
    EntryPath entryPath,
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
    entryPath = Objects.requireNonNull(entryPath, "entryPath");
    queuedCommands = List.copyOf(Objects.requireNonNull(queuedCommands, "queuedCommands"));
    toolSiblings = List.copyOf(Objects.requireNonNull(toolSiblings, "toolSiblings"));
    liveState = Objects.requireNonNull(liveState, "liveState");
    if (generation != null) {
      validateGeneration(thread, entryPath, generation);
    }
    stopReceipts = List.copyOf(Objects.requireNonNull(stopReceipts, "stopReceipts"));
    unconfirmedExecutions =
        List.copyOf(Objects.requireNonNull(unconfirmedExecutions, "unconfirmedExecutions"));
    if (incompleteChildJoins < 0) {
      throw new IllegalArgumentException("incompleteChildJoins must not be negative");
    }
  }

  /** Driver 投影必须归属于本 Thread 的同一个 open turn；公共快照不接受跨 Thread/跨 turn 的外来状态。 */
  private static void validateGeneration(
      ThreadState thread, EntryPath path, GenerationProjection generation) {
    Entry turn =
        path.openTurnStart()
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
    if (path.entries().stream()
        .noneMatch(entry -> entry.id().equals(generation.requestHeadEntryId()))) {
      throw new IllegalStateException(
          "driver generation request head "
              + generation.requestHeadEntryId()
              + " is not on the thread path");
    }
    if (generation.resultEntryId() != null
        && path.entries().stream()
            .noneMatch(entry -> entry.id().equals(generation.resultEntryId()))) {
      throw new IllegalStateException(
          "driver generation result entry "
              + generation.resultEntryId()
              + " is not on the thread path");
    }
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
   * 运行时状态投影为三态（{@link ThreadRuntimeStatus#RUNNING} / {@link ThreadRuntimeStatus#WAITING} / {@link
   * ThreadRuntimeStatus#IDLE}），与持久执行控制 {@link ThreadExecutionControl} 正交。
   */
  public ThreadRuntimeStatus runtimeStatus() {
    boolean hasOpenCompaction =
        entryPath
            .openTurnStart()
            .map(
                entry ->
                    entry.payload() instanceof TurnStartPayload start
                        && start.reason() == TurnStartReason.COMPACTION
                        && thread.id().equals(start.ownerThreadId()))
            .orElse(false);
    boolean hasCompactionObligation = hasOpenCompaction || hasIncompleteOwnerChildJoin;
    boolean hasInputDemand =
        ThreadInputDemand.hasQueuedDemand(queuedCommands) || unconsumedAppliedNotifications;
    return ThreadActivityEvaluator.evaluate(
        thread.executionControl(),
        liveState.isLive(),
        hasCompactionObligation,
        hasInputDemand,
        incompleteChildJoins,
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
   * 当前 root-to-head 上的模型工作轮数：{@code INPUT} 与 {@code CONTINUATION} 计入，{@code COMPACTION} 与 {@code
   * STOP} 不计。resume 后的新路径继续累加，已离开当前 head 的分支不计。
   */
  public int turnCount() {
    int count = 0;
    for (Entry entry : entryPath.entries()) {
      if (entry.payload() instanceof TurnStartPayload start
          && start.reason() != TurnStartReason.COMPACTION
          && start.reason() != TurnStartReason.STOP) {
        count++;
      }
    }
    return count;
  }

  /** 当前 root-to-head 上 ASSISTANT {@code MESSAGE} 的 {@link ToolCallMessageContent} 数量。 */
  public int toolCallCount() {
    int count = 0;
    for (Entry entry : entryPath.entries()) {
      if (entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        for (AgentMessageContent content : message.message().contents()) {
          if (content instanceof ToolCallMessageContent) {
            count++;
          }
        }
      }
    }
    return count;
  }

  /**
   * 仅当运行时状态为 {@link ThreadRuntimeStatus#IDLE} 且当前 head 是 {@code TURN_END} 时返回其 outcome；其它状态与 head
   * 一律为 null。
   */
  public TurnEndOutcome outcome() {
    if (runtimeStatus() != ThreadRuntimeStatus.IDLE) {
      return null;
    }
    if (entryPath.head().payload() instanceof TurnEndPayload end) {
      return end.outcome();
    }
    return null;
  }
}
