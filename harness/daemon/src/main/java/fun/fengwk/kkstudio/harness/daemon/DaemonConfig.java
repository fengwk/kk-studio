package fun.fengwk.kkstudio.harness.daemon;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Daemon 独立进程的连接与执行配置。 */
public record DaemonConfig(
    URI gatewayUri,
    String environmentId,
    String daemonId,
    Duration heartbeatInterval,
    Duration initialReconnectDelay,
    Duration maxReconnectDelay,
    Duration defaultToolTimeout,
    String gatewayToken) {

  public DaemonConfig {
    gatewayUri = Objects.requireNonNull(gatewayUri, "gatewayUri");
    if (!"ws".equals(gatewayUri.getScheme()) && !"wss".equals(gatewayUri.getScheme())) {
      throw new IllegalArgumentException("gatewayUri must use ws or wss");
    }
    environmentId = requireNonBlank(environmentId, "environmentId");
    daemonId = requireNonBlank(daemonId, "daemonId");
    heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
    initialReconnectDelay = requireNonNegative(initialReconnectDelay, "initialReconnectDelay");
    maxReconnectDelay = requirePositive(maxReconnectDelay, "maxReconnectDelay");
    if (initialReconnectDelay.compareTo(maxReconnectDelay) > 0) {
      throw new IllegalArgumentException("initialReconnectDelay must not exceed maxReconnectDelay");
    }
    defaultToolTimeout = requirePositive(defaultToolTimeout, "defaultToolTimeout");
    gatewayToken = requireNonBlank(gatewayToken, "gatewayToken");
  }

  /** 从 JVM system properties 读取可直接启动的最小配置。 */
  public static DaemonConfig fromSystemProperties() {
    return new DaemonConfig(
        URI.create(requiredProperty("kkstudio.daemon.gateway-uri")),
        requiredProperty("kkstudio.daemon.environment-id"),
        System.getProperty("kkstudio.daemon.id", UUID.randomUUID().toString()),
        durationProperty("kkstudio.daemon.heartbeat", Duration.ofSeconds(15)),
        durationProperty("kkstudio.daemon.reconnect-initial", Duration.ofSeconds(1)),
        durationProperty("kkstudio.daemon.reconnect-max", Duration.ofSeconds(30)),
        durationProperty("kkstudio.daemon.tool-timeout", Duration.ofMinutes(5)),
        requiredProperty("kkstudio.daemon.gateway-token"));
  }

  private static Duration durationProperty(String name, Duration defaultValue) {
    String value = System.getProperty(name);
    return value == null || value.isBlank() ? defaultValue : Duration.parse(value);
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing required system property: " + name);
    }
    return value;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static Duration requirePositive(Duration value, String name) {
    value = Objects.requireNonNull(value, name);
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static Duration requireNonNegative(Duration value, String name) {
    value = Objects.requireNonNull(value, name);
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }
}
