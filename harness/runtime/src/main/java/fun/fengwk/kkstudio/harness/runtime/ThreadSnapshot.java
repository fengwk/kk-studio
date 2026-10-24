package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadActivityEvaluator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.Objects;

/**
 * 一致性加锁下的一个 Thread 的不可变投影：durable {@link ThreadState}、当前 root-to-head {@link EntryPath}、已入队
 * Commands（不可变 list），以及仅与当前 live context 分类器匹配的 {@link ModelInvocation} / Tool siblings / 尚未物化的失败
 * attempts。
 *
 * <p>IDLE_OR_HISTORICAL 与 CONTINUATION_DUE snapshot 不暴露 Model 与 tools；Model context 仅暴露 Model；Tool
 * context 暴露 Model 与全部 Tool siblings；只有 Model context 会附带尚未物化为 Entry 的失败 attempts。
 */
public record ThreadSnapshot(
    ThreadState thread,
    EntryPath entryPath,
    List<ThreadCommand> queuedCommands,
    ModelInvocation model,
    List<ToolInvocation> toolSiblings,
    List<ModelAttemptFailureProjection> modelAttemptFailures,
    List<StoppedThreadReceipt> stopReceipts,
    int incompleteChildJoins,
    boolean unconsumedAppliedNotifications) {

  public ThreadSnapshot {
    thread = Objects.requireNonNull(thread, "thread");
    entryPath = Objects.requireNonNull(entryPath, "entryPath");
    queuedCommands = List.copyOf(Objects.requireNonNull(queuedCommands, "queuedCommands"));
    toolSiblings = List.copyOf(Objects.requireNonNull(toolSiblings, "toolSiblings"));
    modelAttemptFailures =
        List.copyOf(Objects.requireNonNull(modelAttemptFailures, "modelAttemptFailures"));
    stopReceipts = List.copyOf(Objects.requireNonNull(stopReceipts, "stopReceipts"));
    if (incompleteChildJoins < 0) {
      throw new IllegalArgumentException("incompleteChildJoins must not be negative");
    }
  }

  /**
   * 运行时状态投影为三态（{@link ThreadRuntimeStatus#RUNNING} / {@link ThreadRuntimeStatus#WAITING} / {@link
   * ThreadRuntimeStatus#IDLE}），与持久执行控制 {@link ThreadExecutionControl} 正交（STOP 绝不直接覆盖本状态）。
   *
   * <ul>
   *   <li>本地活跃流程未结束（活跃 Model/Tool/Continuation/pending apply 或 open compaction）投影为 {@code RUNNING}；
   *   <li>待启动输入需求仅在 {@code RUNNABLE} 下体现为本地待启动 {@code RUNNING}；{@code STOPPED} 下保留通知不自启，不得显示为
   *       RUNNING；
   *   <li>本地已让出执行但存在未完成直接 NOTIFY_PARENT 子任务（{@code incompleteChildJoins > 0}）投影为 {@code WAITING}；
   *   <li>无本地义务且无未完成直接子任务投影为 {@code IDLE}。
   * </ul>
   */
  public ThreadRuntimeStatus runtimeStatus() {
    ThreadContext context =
        new ThreadContextClassifier().classify(thread, entryPath, model, toolSiblings);
    boolean hasOpenCompaction =
        entryPath
            .openTurnStart()
            .map(
                entry ->
                    entry.payload() instanceof TurnStartPayload start
                        && start.reason() == TurnStartReason.COMPACTION
                        && thread.id().equals(start.ownerThreadId()))
            .orElse(false);
    boolean hasInputDemand = !queuedCommands.isEmpty() || unconsumedAppliedNotifications;
    return ThreadActivityEvaluator.evaluate(
        thread.executionControl(),
        context,
        hasOpenCompaction,
        hasInputDemand,
        incompleteChildJoins);
  }

  /**
   * 本 Thread 的统一执行事实投影：由排队命令与 {@link ThreadContextClassifier} 对本 Thread 当前适用 Model/Tool 的
   * 分类结果唯一决定，不递归查询后代、不接触 Store。
   *
   * <p>三个事实彼此正交：{@code hasWork} 覆盖排队命令、合法本地非终态 Model、本地 READY/RUNNING 工具与 owner 归属本 Thread 的
   * continuation（忽略 TURN_END 后的尾部控制 Entry）；{@code waitingForHuman} 只覆盖本地工具的 {@code
   * WAITING_APPROVAL}/{@code WAITING_INPUT}；{@code hasUnknownToolOutcome} 只覆盖本地工具的 {@code UNKNOWN}
   * 终态。投影不读取持久执行控制：{@link ThreadExecutionControl#STOPPED} 不覆盖仍存在的本地调用事实。 Stop
   * 清理调用后，本投影不提供外部进程或远端请求的物理退出确认。
   */
  public ThreadExecutionFacts executionFacts() {
    ThreadContext context =
        new ThreadContextClassifier().classify(thread, entryPath, model, toolSiblings);
    boolean hasWork =
        !queuedCommands.isEmpty()
            || context instanceof ThreadContext.ModelActive
            || context instanceof ThreadContext.ContinuationDue;
    boolean waitingForHuman = false;
    boolean hasUnknownToolOutcome = false;
    // 分类器已经核验 siblings 的当前适用性；历史或非 Tool 上下文不允许携带 siblings。
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
