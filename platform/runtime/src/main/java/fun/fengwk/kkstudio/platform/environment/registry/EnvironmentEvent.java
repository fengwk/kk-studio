package fun.fengwk.kkstudio.platform.environment.registry;

import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventLevel;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventType;

import java.time.Instant;
import java.util.Objects;

/**
 * Environment {@code recent_events} 中的单条运维事件投影。
 *
 * <p>事件只记录结构化、去敏的运行事实：{@code message} 必须是程序构造的固定或严格受限文本，绝不承载 Daemon stdout、凭据、 带 userinfo 的 Git URL
 * 或签名地址。连接生命周期事件由 {@link EnvironmentRegistry} 的围栏 SQL 原子追加。
 */
public record EnvironmentEvent(
    Instant time, EnvironmentEventLevel level, EnvironmentEventType type, String message) {

  /** 事件说明的字符上限。 */
  public static final int MAX_MESSAGE_CHARS = 500;

  public EnvironmentEvent {
    time = Objects.requireNonNull(time, "time");
    // 级别与类型是封闭枚举，null 一律以 NPE 拒绝，且先于 message 校验。
    level = Objects.requireNonNull(level, "level");
    type = Objects.requireNonNull(type, "type");
    message = requireMessage(message);
  }

  private static String requireMessage(String value) {
    if (value == null) {
      throw new IllegalArgumentException("message must not be null");
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("message must not be blank");
    }
    if (trimmed.length() > MAX_MESSAGE_CHARS) {
      throw new IllegalArgumentException(
          "message must not exceed " + MAX_MESSAGE_CHARS + " characters");
    }
    if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("message must not contain control characters");
    }
    return trimmed;
  }
}
