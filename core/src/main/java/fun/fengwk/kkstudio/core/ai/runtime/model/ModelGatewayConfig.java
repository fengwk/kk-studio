package fun.fengwk.kkstudio.core.ai.runtime.model;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;

/** 不可变的 {@link CoreModelGateway} 部署配置。 */
public record ModelGatewayConfig(Duration busyRetryDelay) {

  /**
   * busyRetryDelay：executor 拒绝提交后 {@link
   * fun.fengwk.kkstudio.harness.runtime.port.ModelGateway.Busy} 的重试延迟。测试用冻结载体；生产 {@link
   * CoreModelGateway} 在每次 start 从 SystemSettingsSnapshot 现读 {@code
   * tool.modelGatewayBusyRetryMillis}。
   */
  public ModelGatewayConfig {
    busyRetryDelay =
        HarnessStoreTime.requireWholeMillisecondDuration(busyRetryDelay, "busyRetryDelay");
  }
}
