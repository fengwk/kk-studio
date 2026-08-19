package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Model response turn 结束的 immutable durable 边界。
 *
 * <p>精确规则：COMPLETED 的 continueModel 可 true/false，reason 与 closeRequestId 必须 null；FAILED 必须
 * continueModel=false、reason=TURN_FAILED / OUTPUT_TRUNCATED / CONTENT_FILTERED、closeRequestId null；
 * STOPPED 必须 reason=USER_STOP、continueModel=false、closeRequestId 非 null；CANCELLED 必须
 * reason=HISTORY_CUT 或 CANCELLED、continueModel=false，closeRequestId 可为 null。
 *
 * <p>STOPPED 的 {@code closeRequestId} 是客户端 stop 幂等键，作用域按被引用的 TURN_START {@code ownerThreadId}
 * 界定（Runtime 做 Session 级查找），raw 值可在不同 Thread 上独立复用。
 */
public record TurnEndPayload(
    UUID turnStartEntryId,
    TurnEndOutcome outcome,
    boolean continueModel,
    TurnEndReason reason,
    UUID closeRequestId)
    implements EntryPayload {

  public TurnEndPayload {
    Objects.requireNonNull(turnStartEntryId, "turnStartEntryId");
    outcome = Objects.requireNonNull(outcome, "outcome");
    switch (outcome) {
      case COMPLETED -> {
        if (reason != null || closeRequestId != null) {
          throw new IllegalArgumentException(
              "completed turns must not carry reason or closeRequestId");
        }
      }
      case FAILED -> {
        if (reason != TurnEndReason.TURN_FAILED
            && reason != TurnEndReason.OUTPUT_TRUNCATED
            && reason != TurnEndReason.CONTENT_FILTERED) {
          throw new IllegalArgumentException(
              "failed turns require reason TURN_FAILED, OUTPUT_TRUNCATED or CONTENT_FILTERED");
        }
        if (continueModel) {
          throw new IllegalArgumentException("continueModel must be false for failed turns");
        }
        if (closeRequestId != null) {
          throw new IllegalArgumentException("failed turns must not carry closeRequestId");
        }
      }
      case STOPPED -> {
        if (reason != TurnEndReason.USER_STOP) {
          throw new IllegalArgumentException("stopped turns require reason USER_STOP");
        }
        if (continueModel) {
          throw new IllegalArgumentException("continueModel must be false for stopped turns");
        }
        Objects.requireNonNull(closeRequestId, "closeRequestId");
      }
      case CANCELLED -> {
        if (reason != TurnEndReason.HISTORY_CUT && reason != TurnEndReason.CANCELLED) {
          throw new IllegalArgumentException(
              "cancelled turns require reason HISTORY_CUT or CANCELLED");
        }
        if (continueModel) {
          throw new IllegalArgumentException("continueModel must be false for cancelled turns");
        }
      }
    }
  }

  @Override
  public EntryType type() {
    return EntryType.TURN_END;
  }
}
