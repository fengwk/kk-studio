package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;

import java.util.Objects;

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
    long firstKeptEntryId,
    long cutEntryId,
    Long turnPrefixStartEntryId) {

  public CompactionRequest {
    phase = Objects.requireNonNull(phase, "phase");
    trigger = Objects.requireNonNull(trigger, "trigger");
    if (tokensBefore < 0) {
      throw new IllegalArgumentException("tokensBefore must not be negative");
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
        throw new IllegalArgumentException("FULL compaction must not carry turnPrefixStartEntryId");
      }
    } else if (turnPrefixStartEntryId == null) {
      throw new IllegalArgumentException("split compaction phases require turnPrefixStartEntryId");
    }
  }
}
