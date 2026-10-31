package fun.fengwk.kkstudio.harness.environment.server;

import java.util.Objects;

/**
 * Daemon 握手请求参数。
 *
 * @param registrationToken 准入注册令牌
 * @param daemonInstanceId Daemon 实例唯一标识
 */
public record DaemonHandshake(String registrationToken, String daemonInstanceId) {

  public DaemonHandshake {
    Objects.requireNonNull(registrationToken, "registrationToken must not be null");
    Objects.requireNonNull(daemonInstanceId, "daemonInstanceId must not be null");
  }
}
