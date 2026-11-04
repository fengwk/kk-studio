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

  /** 在创建 dispatcher 前 fail-fast，避免无效 lease、过度 claim 或退化轮询启动。 */
  public void validate() {
    if (workerConcurrency < 1) {
      throw new IllegalArgumentException(
          "kk-studio.canvas.function.runtime.worker-concurrency must be at least 1");
    }
    if (maxDispatchTasks < 1 || maxDispatchTasks > workerConcurrency) {
      throw new IllegalArgumentException(
          "kk-studio.canvas.function.runtime.max-dispatch-tasks must be between 1 and worker-concurrency");
    }
    if (leaseDurationMillis < 1
        || heartbeatIntervalMillis < 1
        || pollIntervalMillis < 1
        || rejectionDelayMillis < 1) {
      throw new IllegalArgumentException(
          "Canvas Function runtime durations must be positive whole milliseconds");
    }
    if (heartbeatIntervalMillis >= leaseDurationMillis) {
      throw new IllegalArgumentException(
          "kk-studio.canvas.function.runtime.heartbeat-interval-millis must be less than lease-duration-millis");
    }
  }
}
