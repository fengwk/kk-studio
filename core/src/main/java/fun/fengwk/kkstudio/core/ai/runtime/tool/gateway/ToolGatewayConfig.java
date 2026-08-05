package fun.fengwk.kkstudio.core.ai.runtime.tool.gateway;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;

/** 不可变的 {@link CoreToolGateway} 部署配置。 */
public record ToolGatewayConfig(Duration busyRetryDelay, Duration overloadRetryDelay) {

  /** 未显式配置时：offline Busy 的快速重试延迟与本地 executor 过载的退避延迟。 */
  public static final ToolGatewayConfig DEFAULT =
      new ToolGatewayConfig(Duration.ofSeconds(1), Duration.ofSeconds(5));

  public ToolGatewayConfig {
    busyRetryDelay =
        HarnessStoreTime.requireWholeMillisecondDuration(busyRetryDelay, "busyRetryDelay");
    overloadRetryDelay =
        HarnessStoreTime.requireWholeMillisecondDuration(overloadRetryDelay, "overloadRetryDelay");
  }
}
