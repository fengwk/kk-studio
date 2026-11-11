package fun.fengwk.kkstudio.platform.environment.registry;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Environment {@code recent_events} 中的单条运维事件投影。
 *
 * <p>事件只记录结构化、去敏的运行事实：{@code message} 必须是程序构造的固定或严格受限文本，绝不承载 Daemon stdout、凭据、 带 userinfo 的 Git URL
 * 或签名地址。连接生命周期事件由 {@link EnvironmentRegistry} 的围栏 SQL 原子追加。
 */
public record EnvironmentEvent(Instant time, String level, String type, String message) {

  public static final String LEVEL_INFO = "INFO";
  public static final String LEVEL_WARN = "WARN";
  public static final String LEVEL_ERROR = "ERROR";

  public static final String TYPE_CONNECTING = "CONNECTING";
  public static final String TYPE_READY = "READY";
  public static final String TYPE_DISCONNECTED = "DISCONNECTED";
  public static final String TYPE_UPDATE_FAILED = "UPDATE_FAILED";

  /** 事件说明的字符上限。 */
  public static final int MAX_MESSAGE_CHARS = 500;

  private static final Set<String> LEVELS = Set.of(LEVEL_INFO, LEVEL_WARN, LEVEL_ERROR);
  private static final Set<String> TYPES =
      Set.of(TYPE_CONNECTING, TYPE_READY, TYPE_DISCONNECTED, TYPE_UPDATE_FAILED);

  public EnvironmentEvent {
    time = Objects.requireNonNull(time, "time");
    if (!LEVELS.contains(level)) {
      throw new IllegalArgumentException("unknown environment event level: " + level);
    }
    if (!TYPES.contains(type)) {
      throw new IllegalArgumentException("unknown environment event type: " + type);
    }
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
