package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;

import java.util.Objects;
import java.util.UUID;

/**
 * 压缩 Model 调用的冻结元数据（与正常调用区分的唯一 durable 事实）。
 *
 * <p>压缩调用不允许携带任何 tool / skill binding，且永远只构建一个 SYSTEM + 一个 USER summary 请求；{@code firstKeptEntryId}
 * / {@code cutEntryId} / {@code turnPrefixStartEntryId} 是 planner 在 plan 事务内冻结的切分事实， 由
 * ResolvedRequestValidator 与 TurnPlan 的 {@link
 * fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation} 严格比对。
 */
public record CompactionRequest(
    CompactionPhase phase,
    CompactionTrigger trigger,
    long tokensBefore,
    UUID firstKeptEntryId,
    UUID cutEntryId,
    UUID turnPrefixStartEntryId) {

  public CompactionRequest {
    phase = Objects.requireNonNull(phase, "phase");
    trigger = Objects.requireNonNull(trigger, "trigger");
    if (tokensBefore < 0) {
      throw new IllegalArgumentException("tokensBefore must not be negative");
    }
    Objects.requireNonNull(firstKeptEntryId, "firstKeptEntryId");
    Objects.requireNonNull(cutEntryId, "cutEntryId");
    if (phase == CompactionPhase.FULL) {
      if (turnPrefixStartEntryId != null) {
        throw new IllegalArgumentException("FULL compaction must not carry turnPrefixStartEntryId");
      }
    } else if (turnPrefixStartEntryId == null) {
      throw new IllegalArgumentException("split compaction phases require turnPrefixStartEntryId");
    }
  }
}
