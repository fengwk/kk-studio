package fun.fengwk.kkstudio.core.studio.realtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Canvas realtime 投影配置：bounded Redis Stream patch cache。 */
@ConfigurationProperties(prefix = "kk-studio.canvas.realtime")
@Data
public class CanvasRealtimeProperties {

  /** Redis Stream 键前缀，完整键为 {@code {prefix}{canvasId}:changes}。 */
  private String redisPrefix = "kk-studio:canvas:";

  /** 每个 Canvas 的 changes Stream 最大长度（exact trim）。 */
  private long redisMaxLength = 5_000L;

  /** 校验并返回规范键。 */
  public String key(String canvasIdText) {
    if (redisPrefix == null || redisPrefix.isBlank()) {
      throw new IllegalStateException("kk-studio.canvas.realtime.redis-prefix must not be blank");
    }
    if (redisMaxLength <= 0L) {
      throw new IllegalStateException(
          "kk-studio.canvas.realtime.redis-max-length must be positive");
    }
    return redisPrefix + canvasIdText + ":changes";
  }
}
