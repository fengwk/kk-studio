package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;

/**
 * Daemon 对一条受管更新命令的阶段回执。
 *
 * <p>它只报告 Daemon 侧的准备进度，不是最终成功事实：最终成功由更新后 Daemon 以目标版本重新 READY 确认。因此断开连接不等于失败，
 * 调用方不得据缺失回执推断失败。{@code message} 只承载去敏、有界的失败说明。
 */
public record DaemonUpdateResult(String operationId, DaemonUpdatePhase phase, String message) {

  /** 失败说明上界。 */
  public static final int MAX_MESSAGE_CHARS = 500;

  public DaemonUpdateResult {
    operationId = Objects.requireNonNull(operationId, "operationId");
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId must not be blank");
    }
    phase = Objects.requireNonNull(phase, "phase");
    message = validateMessage(message);
  }

  public static DaemonUpdateResult accepted(String operationId) {
    return new DaemonUpdateResult(operationId, DaemonUpdatePhase.ACCEPTED, null);
  }

  public static DaemonUpdateResult prepared(String operationId) {
    return new DaemonUpdateResult(operationId, DaemonUpdatePhase.PREPARED, null);
  }

  public static DaemonUpdateResult failed(String operationId, String message) {
    return new DaemonUpdateResult(operationId, DaemonUpdatePhase.FAILED, message);
  }

  private static String validateMessage(String value) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("message must not be blank when present");
    }
    if (value.length() > MAX_MESSAGE_CHARS) {
      throw new IllegalArgumentException("message exceeds " + MAX_MESSAGE_CHARS + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("message must not contain ISO control characters");
    }
    return value;
  }
}
