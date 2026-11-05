package fun.fengwk.kkstudio.canvas.infra.function;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Canvas Function durable worker 的部署级配置。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.canvas.function.runtime")
public class CanvasFunctionRuntimeProperties {

  private int workerConcurrency = 2;

  private int maxDispatchTasks = 2;

  private long leaseDurationMillis = 30_000;

  private long heartbeatIntervalMillis = 10_000;

  private long pollIntervalMillis = 1_000;

  private long rejectionDelayMillis = 1_000;
}
