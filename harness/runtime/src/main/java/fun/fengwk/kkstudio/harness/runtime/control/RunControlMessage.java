package fun.fengwk.kkstudio.harness.runtime.control;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;

import java.time.Instant;
import java.util.Objects;

/**
 * 已接受并持久化的 control 行不可变快照。
 *
 * <p>字段一致性约束：
 *
 * <ul>
 *   <li>id/会话 id 必须正整数；run id 可为 null（用于 FOLLOW_UP 在无 active Run 时直接 PROMOTED）
 *   <li>kind/message/消费模式/status/createdAt 不可空；message.role 必须为 USER
 *   <li>PENDING 时 consumedRunId/consumedEntryId/consumedAt 必须全部为 null
 *   <li>CONSUMED/PROMOTED 时三个 consumed 字段必须同时存在且非 null
 *   <li>CLEARED 时仅 consumedAt 必须存在；consumedRunId/consumedEntryId 必须为 null
 * </ul>
 */
public record RunControlMessage(
    long id,
    long sessionId,
    Long originalRunId,
    RunControlKind kind,
    ControlConsumptionMode consumptionMode,
    AgentMessage message,
    RunControlStatus status,
    Long consumedRunId,
    Long consumedEntryId,
    Instant createdAt,
    Instant consumedAt) {

  public RunControlMessage {
    if (id <= 0) {
      throw new IllegalArgumentException("control id must be positive");
    }
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    if (originalRunId != null && originalRunId <= 0) {
      throw new IllegalArgumentException("originalRunId must be positive when present");
    }
    kind = Objects.requireNonNull(kind, "kind");
    consumptionMode = Objects.requireNonNull(consumptionMode, "consumptionMode");
    message = Objects.requireNonNull(message, "message");
    if (message.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("control message must have USER role");
    }
    status = Objects.requireNonNull(status, "status");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    validateConsumedConsistency(status, consumedRunId, consumedEntryId, consumedAt);
  }

  private static void validateConsumedConsistency(
      RunControlStatus status, Long consumedRunId, Long consumedEntryId, Instant consumedAt) {
    switch (status) {
      case PENDING -> {
        if (consumedRunId != null || consumedEntryId != null || consumedAt != null) {
          throw new IllegalArgumentException("PENDING control must not have consumed fields");
        }
      }
      case CONSUMED, PROMOTED -> {
        if (consumedRunId == null
            || consumedRunId <= 0
            || consumedEntryId == null
            || consumedEntryId <= 0
            || consumedAt == null) {
          throw new IllegalArgumentException(
              status + " control must carry consumedRunId, consumedEntryId and consumedAt");
        }
      }
      case CLEARED -> {
        if (consumedAt == null) {
          throw new IllegalArgumentException("CLEARED control must carry consumedAt");
        }
        if (consumedRunId != null || consumedEntryId != null) {
          throw new IllegalArgumentException(
              "CLEARED control must not fabricate consumedRunId or consumedEntryId");
        }
      }
    }
  }
}
