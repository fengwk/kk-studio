package fun.fengwk.kkstudio.core.studio.function.opencli;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Seedance 真实提交开关、workspace 与有界轮询预算。 */
@ConfigurationProperties("kk-studio.canvas.function.seedance")
@Data
public class SeedanceCanvasProperties {

  private boolean enabled;
  private String workspaceId;
  private int retry;
  private Duration hubExecutionTimeout = Duration.ofMinutes(10);
  private Duration statusPollInterval = Duration.ofSeconds(30);
  private Duration maxWait = Duration.ofMinutes(30);

  public void validate() {
    if (workspaceId != null) {
      workspaceId = workspaceId.strip();
      if (workspaceId.isEmpty() || workspaceId.length() > 256) {
        throw new IllegalArgumentException("workspaceId must be absent or 1..256 characters");
      }
    }
    if (enabled && workspaceId == null) {
      throw new IllegalArgumentException("Seedance workspaceId is required when enabled");
    }
    if (retry < 0 || retry > 5) {
      throw new IllegalArgumentException("retry must be between 0 and 5");
    }
    requireBounded(
        hubExecutionTimeout, Duration.ofSeconds(1), Duration.ofMinutes(30), "hubExecutionTimeout");
    requireBounded(
        statusPollInterval, Duration.ofMillis(1), Duration.ofMinutes(5), "statusPollInterval");
    requireBounded(maxWait, Duration.ofSeconds(1), Duration.ofHours(4), "maxWait");
  }

  private static void requireBounded(Duration value, Duration min, Duration max, String field) {
    if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw new IllegalArgumentException(field + " is outside the supported range");
    }
  }
}
