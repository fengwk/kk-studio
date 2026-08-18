package fun.fengwk.kkstudio.core.studio.realtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Canvas realtime 投影的部署级 Redis 键配置；Stream 最大长度来自 SystemSettings.advanced.canvasRealtimeMaxLength。
 */
@ConfigurationProperties(prefix = "kk-studio.canvas.realtime")
@Data
public class CanvasRealtimeProperties {

  /** Redis Stream 键前缀，完整键为 {@code {prefix}{canvasId}:changes}。 */
  private String redisPrefix = "kk-studio:canvas:";

  /** 校验并返回规范键。 */
  public String key(String canvasIdText) {
    if (redisPrefix == null || redisPrefix.isBlank()) {
      throw new IllegalStateException("kk-studio.canvas.realtime.redis-prefix must not be blank");
    }
    return redisPrefix + canvasIdText + ":changes";
  }
}
