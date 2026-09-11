package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

/** 环境路由被其他活跃连接持有：daemon 应退避重试，连接以 {@code RETRY_LATER} 错误关闭。 */
public final class DaemonRetryLaterException extends DaemonProtocolException {

  public DaemonRetryLaterException(String message) {
    super(message);
  }
}
