package fun.fengwk.kkstudio.core.ai.runtime.model;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;

/** 不可变的 {@link CoreModelGateway} 部署配置。 */
public record ModelGatewayConfig(Duration busyRetryDelay) {

  /**
   * 未显式配置时，executor 拒绝提交后 {@link fun.fengwk.kkstudio.harness.runtime.port.ModelGateway.Busy} 的重试延迟。
   */
  public static final ModelGatewayConfig DEFAULT = new ModelGatewayConfig(Duration.ofSeconds(5));

  public ModelGatewayConfig {
    busyRetryDelay =
        HarnessStoreTime.requireWholeMillisecondDuration(busyRetryDelay, "busyRetryDelay");
  }
}
