package fun.fengwk.kkstudio.platform.daemon.server;

import java.time.Duration;
import java.util.Objects;

/** 核心每次判定现读的运行期设置。 */
public record EnvironmentServerSettings(
    Duration heartbeatTimeout,
    long maxResourceBytes,
    long temporaryResourceTtlSeconds,
    long temporaryResourceCleanupIntervalSeconds,
    String proxyUrl,
    String noProxyHosts) {

  public EnvironmentServerSettings {
    heartbeatTimeout = Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
    noProxyHosts = Objects.requireNonNull(noProxyHosts, "noProxyHosts");
    if (heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) {
      throw new IllegalArgumentException("heartbeatTimeout must be positive");
    }
    if (maxResourceBytes <= 0L) {
      throw new IllegalArgumentException("maxResourceBytes must be positive");
    }
    if (temporaryResourceTtlSeconds <= 0L) {
      throw new IllegalArgumentException("temporaryResourceTtlSeconds must be positive");
    }
    if (temporaryResourceCleanupIntervalSeconds <= 0L) {
      throw new IllegalArgumentException(
          "temporaryResourceCleanupIntervalSeconds must be positive");
    }
  }
}
