package fun.fengwk.kkstudio.platform.ai.environment.gateway;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

/**
 * Environment daemon 连接的不可变 READY 桥。
 *
 * <p>daemon 变为 READY 后、协议锁释放时被调用。任何栈外调度由实现自行负责。
 */
@FunctionalInterface
public interface EnvironmentReadyListener {

  /** 通知绑定到 {@code environmentName} 的 environment 已 READY，可以进行 ENVIRONMENT tool 派发。 */
  void onEnvironmentReady(EnvironmentName environmentName);
}
