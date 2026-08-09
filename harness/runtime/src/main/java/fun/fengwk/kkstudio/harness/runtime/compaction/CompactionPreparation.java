package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;

import java.util.List;
import java.util.Objects;

/**
 * 一次压缩 turn 的完整切分准备（transient，不持久化）。
 *
 * <p>由纯 {@link CompactionPlanner} 在 plan 事务内基于 source EntryPath 计算，随 {@code TurnPlan} 传给 Resolver
 * 构造冻结请求，并由 {@code ResolvedRequestValidator} 与请求中的 {@link
 * fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest} 做严格机械比对。{@code
 * messagesToSummarize} 是本阶段实际要摘要的上下文消息（FULL/HISTORY 为历史段，TURN_PREFIX 为切分 turn 前缀段）； {@code
 * previousSummary} 仅 FULL/HISTORY 存在上一份 complete 压缩 summary 时非空。
 */
public record CompactionPreparation(
    CompactionPhase phase,
    CompactionTrigger trigger,
    long tokensBefore,
    long contextWindow,
    long firstKeptEntryId,
    long cutEntryId,
    Long turnPrefixStartEntryId,
    String previousSummary,
    List<AgentMessage> messagesToSummarize) {

  public CompactionPreparation {
    phase = Objects.requireNonNull(phase, "phase");
    trigger = Objects.requireNonNull(trigger, "trigger");
    if (tokensBefore < 0) {
      throw new IllegalArgumentException("tokensBefore must not be negative");
    }
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    if (firstKeptEntryId <= 0) {
      throw new IllegalArgumentException("firstKeptEntryId must be positive");
    }
    if (cutEntryId <= 0) {
      throw new IllegalArgumentException("cutEntryId must be positive");
    }
    if (turnPrefixStartEntryId != null && turnPrefixStartEntryId <= 0) {
      throw new IllegalArgumentException("turnPrefixStartEntryId must be positive");
    }
    if (phase == CompactionPhase.FULL) {
      if (turnPrefixStartEntryId != null) {
        throw new IllegalArgumentException(
            "FULL preparation must not carry turnPrefixStartEntryId");
      }
    } else if (turnPrefixStartEntryId == null) {
      throw new IllegalArgumentException("split compaction phases require turnPrefixStartEntryId");
    }
    messagesToSummarize =
        List.copyOf(Objects.requireNonNull(messagesToSummarize, "messagesToSummarize"));
  }
}
