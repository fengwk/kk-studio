package fun.fengwk.kkstudio.core.ai.runtime.tool.gateway;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Duration;

/** 不可变的 {@link CoreToolGateway} 部署配置。 */
public record ToolGatewayConfig(Duration busyRetryDelay, Duration overloadRetryDelay) {

  /**
   * busyRetryDelay 与 overloadRetryDelay：offline Busy 的快速重试延迟与本地 executor 过载的退避延迟。由数据库
   * SystemSettings 的 {@code tool.toolGatewayBusyRetryMillis/overloadRetryMillis} 在装配时构造。
   */
  public ToolGatewayConfig {
    busyRetryDelay =
        HarnessStoreTime.requireWholeMillisecondDuration(busyRetryDelay, "busyRetryDelay");
    overloadRetryDelay =
        HarnessStoreTime.requireWholeMillisecondDuration(overloadRetryDelay, "overloadRetryDelay");
  }
}
