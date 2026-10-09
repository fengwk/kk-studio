package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;

import java.util.Objects;
import java.util.UUID;

/**
 * COMPACTION turn 开始处冻结的最小 durable 元数据（挂在 {@link
 * fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload#compaction()} 上）。
 *
 * <p>{@code executionModel} 是压缩执行实际使用的 model selection（解析自普通 Agent 目录中的 {@code compaction}
 * 定义），冻结后不再随 branch settings 变化。
 *
 * <p>{@code cutEntryId} 是本次压缩实际第一个保留的上下文消息；切分 phases（HISTORY / TURN_PREFIX）额外携带 {@code
 * turnPrefixStartEntryId}（切分 turn 内第一个 USER/CUSTOM）。{@code historyCompactionEntryId} 仅机械
 * TURN_PREFIX 第二阶段携带：精确引用紧邻前一个已关闭 HISTORY 压缩结果 Entry；单个超大 Turn 无更早 history 时，direct TURN_PREFIX 保持
 * null。
 */
public record CompactionStart(
    CompactionPhase phase,
    CompactionTrigger trigger,
    ModelSelection executionModel,
    Long outputBudget,
    UUID cutEntryId,
    UUID turnPrefixStartEntryId,
    UUID historyCompactionEntryId,
    UUID childThreadId,
    UUID joinInvocationId) {

  public static CompactionStart pending(
      CompactionPhase phase,
      CompactionTrigger trigger,
      UUID cutEntryId,
      UUID turnPrefixStartEntryId,
      UUID historyCompactionEntryId) {
    return new CompactionStart(
        phase,
        trigger,
        null,
        null,
        cutEntryId,
        turnPrefixStartEntryId,
        historyCompactionEntryId,
        null,
        null);
  }

  public CompactionStart {
    phase = Objects.requireNonNull(phase, "phase");
    trigger = Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(cutEntryId, "cutEntryId");
    boolean allRunGroupNull =
        executionModel == null
            && outputBudget == null
            && childThreadId == null
            && joinInvocationId == null;
    boolean allRunGroupNonNull =
        executionModel != null
            && outputBudget != null
            && childThreadId != null
            && joinInvocationId != null;
    if (!allRunGroupNull && !allRunGroupNonNull) {
      throw new IllegalArgumentException(
          "executionModel, outputBudget, childThreadId, and joinInvocationId must either all be null or all be non-null");
    }
    if (allRunGroupNonNull && outputBudget <= 0L) {
      throw new IllegalArgumentException("outputBudget must be positive");
    }
    if (phase == CompactionPhase.FULL) {
      if (turnPrefixStartEntryId != null || historyCompactionEntryId != null) {
        throw new IllegalArgumentException(
            "FULL compaction must not carry turnPrefixStartEntryId or historyCompactionEntryId");
      }
    } else if (phase == CompactionPhase.HISTORY) {
      if (turnPrefixStartEntryId == null) {
        throw new IllegalArgumentException("HISTORY compaction requires turnPrefixStartEntryId");
      }
      if (historyCompactionEntryId != null) {
        throw new IllegalArgumentException(
            "HISTORY compaction must not carry historyCompactionEntryId");
      }
    } else {
      if (turnPrefixStartEntryId == null) {
        throw new IllegalArgumentException(
            "TURN_PREFIX compaction requires turnPrefixStartEntryId");
      }
    }
  }
}
