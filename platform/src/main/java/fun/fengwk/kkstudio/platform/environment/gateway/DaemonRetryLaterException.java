package fun.fengwk.kkstudio.platform.environment.gateway;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocolException;

/** 非终态重试：HELLO 声称的 {@code EnvironmentName} 当前已被相同 daemon 实例持有活跃路由。 */
public final class DaemonRetryLaterException extends DaemonProtocolException {

  public DaemonRetryLaterException(String message) {
    super(message);
  }
}
