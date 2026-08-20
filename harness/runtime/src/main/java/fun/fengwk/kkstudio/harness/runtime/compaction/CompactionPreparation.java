package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次压缩 turn 的完整切分准备（transient，不持久化）。
 *
 * <p>由纯 {@link CompactionPlanner} 在 plan 事务内基于 source EntryPath 计算，随 {@code TurnPlan} 传给 Resolver
 * 构造冻结请求，并由 {@code ResolvedRequestValidator} 与最终 {@link CompactionStart} 做严格机械比对。Resolver 按 {@code
 * executionModel} 解析实际 context window / max output，再结合 {@code removedPrefixTokens} 计算输出预算；fallback
 * 因此不会错误复用 primary model 的窗口。{@code messagesToSummarize} 是本阶段实际要摘要的上下文消息。
 */
public record CompactionPreparation(
    CompactionPhase phase,
    CompactionTrigger trigger,
    ModelSelection executionModel,
    UUID cutEntryId,
    UUID turnPrefixStartEntryId,
    UUID historyCompactionEntryId,
    String previousSummary,
    List<AgentMessage> messagesToSummarize,
    long removedPrefixTokens) {

  public CompactionPreparation {
    phase = Objects.requireNonNull(phase, "phase");
    trigger = Objects.requireNonNull(trigger, "trigger");
    executionModel = Objects.requireNonNull(executionModel, "executionModel");
    Objects.requireNonNull(cutEntryId, "cutEntryId");
    if (phase == CompactionPhase.FULL) {
      if (turnPrefixStartEntryId != null || historyCompactionEntryId != null) {
        throw new IllegalArgumentException(
            "FULL preparation must not carry turnPrefixStartEntryId or historyCompactionEntryId");
      }
    } else if (phase == CompactionPhase.HISTORY) {
      if (turnPrefixStartEntryId == null) {
        throw new IllegalArgumentException("HISTORY preparation requires turnPrefixStartEntryId");
      }
      if (historyCompactionEntryId != null) {
        throw new IllegalArgumentException(
            "HISTORY preparation must not carry historyCompactionEntryId");
      }
    } else if (turnPrefixStartEntryId == null) {
      throw new IllegalArgumentException("TURN_PREFIX preparation requires turnPrefixStartEntryId");
    }
    messagesToSummarize =
        List.copyOf(Objects.requireNonNull(messagesToSummarize, "messagesToSummarize"));
    if (messagesToSummarize.isEmpty()) {
      throw new IllegalArgumentException("messagesToSummarize must not be empty");
    }
    if (removedPrefixTokens <= 0) {
      throw new IllegalArgumentException("removedPrefixTokens must be positive");
    }
  }

  /** 切分事实（phase/trigger/executionModel/cut/turnPrefixStart/historyCompactionEntryId）的冻结视图。 */
  public CompactionStart frozenStart() {
    return new CompactionStart(
        phase,
        trigger,
        executionModel,
        cutEntryId,
        turnPrefixStartEntryId,
        historyCompactionEntryId);
  }
}
