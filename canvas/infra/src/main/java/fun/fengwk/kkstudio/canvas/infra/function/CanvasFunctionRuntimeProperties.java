package fun.fengwk.kkstudio.canvas.infra.function;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Canvas Function Runtime 的部署级有界 executor 配置。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.canvas.function.runtime")
public class CanvasFunctionRuntimeProperties {

  private int coreSize = 2;

  private int maxSize = 4;

  private int queueCapacity = 64;

  /** 在创建 executor 前 fail-fast，避免以无界或退化线程池启动。 */
  public void validate() {
    if (coreSize < 1) {
      throw new IllegalArgumentException(
          "kk-studio.canvas.function.runtime.core-size must be at least 1");
    }
    if (maxSize < coreSize) {
      throw new IllegalArgumentException(
          "kk-studio.canvas.function.runtime.max-size must not be less than core-size");
    }
    if (queueCapacity < 1) {
      throw new IllegalArgumentException(
          "kk-studio.canvas.function.runtime.queue-capacity must be at least 1");
    }
  }
}
