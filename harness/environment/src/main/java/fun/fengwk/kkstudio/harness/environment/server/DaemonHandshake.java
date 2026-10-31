package fun.fengwk.kkstudio.harness.environment.server;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Daemon 握手请求参数。
 *
 * @param registrationToken 准入注册令牌
 * @param daemonInstanceId Daemon 实例唯一标识
 */
public record DaemonHandshake(String registrationToken, String daemonInstanceId) {

  private static final Pattern UUID_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  public DaemonHandshake {
    Objects.requireNonNull(registrationToken, "registrationToken must not be null");
    if (registrationToken.isBlank()) {
      throw new IllegalArgumentException("registrationToken must not be blank");
    }
    Objects.requireNonNull(daemonInstanceId, "daemonInstanceId must not be null");
    if (!UUID_PATTERN.matcher(daemonInstanceId).matches()) {
      throw new IllegalArgumentException("daemonInstanceId must be a canonical lowercase UUID");
    }
  }
}
