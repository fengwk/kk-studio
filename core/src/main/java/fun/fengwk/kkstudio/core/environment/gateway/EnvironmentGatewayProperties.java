package fun.fengwk.kkstudio.core.environment.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for untrusted Environment Daemon gateway payloads. */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.environment-gateway")
public class EnvironmentGatewayProperties {

  private long maxArtifactBytes = 8L * 1024 * 1024;
  private long maxMessageBytes = 16L * 1024 * 1024;
  private String daemonToken;

  public long requireMaxArtifactBytes() {
    if (maxArtifactBytes <= 0) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.max-artifact-bytes must be positive");
    }
    return maxArtifactBytes;
  }

  /** Returns the bounded text-frame buffer size required by the WebSocket transport. */
  public int requireMaxMessageBytes() {
    if (maxMessageBytes <= 0 || maxMessageBytes > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.max-message-bytes must be between 1 and "
              + Integer.MAX_VALUE);
    }
    return (int) maxMessageBytes;
  }

  /** Returns the deployment-scoped secret required to attach an Environment Daemon. */
  public String requireDaemonToken() {
    if (daemonToken == null || daemonToken.isBlank()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.daemon-token must not be blank");
    }
    return daemonToken;
  }
}
