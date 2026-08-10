package fun.fengwk.kkstudio.core.studio.function;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Canvas Function worker 的有界进程内 executor 配置。 */
@ConfigurationProperties(prefix = "kk-studio.canvas.function.executor")
@Data
public class CanvasFunctionExecutorProperties {
  private int coreSize = 2;
  private int maxSize = 4;
  private int queueCapacity = 64;

  public void validate() {
    if (coreSize <= 0 || maxSize < coreSize || queueCapacity <= 0) {
      throw new IllegalArgumentException(
          "Canvas Function executor requires 0 < coreSize <= maxSize and queueCapacity > 0");
    }
  }
}
