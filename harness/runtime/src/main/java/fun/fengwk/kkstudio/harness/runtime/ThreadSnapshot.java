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
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
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
    List<StoppedThreadReceipt> stopReceipts) {

  public ThreadSnapshot {
    thread = Objects.requireNonNull(thread, "thread");
    entryPath = Objects.requireNonNull(entryPath, "entryPath");
    queuedCommands = List.copyOf(Objects.requireNonNull(queuedCommands, "queuedCommands"));
    toolSiblings = List.copyOf(Objects.requireNonNull(toolSiblings, "toolSiblings"));
    modelAttemptFailures =
        List.copyOf(Objects.requireNonNull(modelAttemptFailures, "modelAttemptFailures"));
    stopReceipts = List.copyOf(Objects.requireNonNull(stopReceipts, "stopReceipts"));
  }

  /**
   * 持久执行控制为 {@link ThreadExecutionControl#STOPPED} 时投影 STOPPED；否则按本地适用上下文投影细分阶段，本地空闲但存在排队命令时
   * 投影 QUEUED。不递归查询后代。
   */
  public ThreadRuntimeStatus runtimeStatus() {
    if (thread.executionControl().isStopped()) {
      return ThreadRuntimeStatus.STOPPED;
    }
    ThreadRuntimeStatus local =
        ThreadRuntimeStatus.from(
            new ThreadContextClassifier().classify(thread, entryPath, model, toolSiblings));
    if (local != ThreadRuntimeStatus.IDLE) {
      return local;
    }
    return queuedCommands.isEmpty() ? ThreadRuntimeStatus.IDLE : ThreadRuntimeStatus.QUEUED;
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
