package fun.fengwk.kkstudio.core.studio.function.opencli;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** GPT Image 2 真实付费提交开关与有界执行预算。 */
@ConfigurationProperties("kk-studio.canvas.function.gpt-image-2")
@Data
public class GptImage2CanvasProperties {

  private boolean paidEnabled;
  private int askTimeoutSeconds = 900;
  private Duration hubExecutionTimeout = Duration.ofMinutes(16);
  private Duration maxWait = Duration.ofMinutes(20);

  public void validate() {
    if (askTimeoutSeconds < 1 || askTimeoutSeconds > 1740) {
      throw new IllegalArgumentException("askTimeoutSeconds must be between 1 and 1740");
    }
    requireBounded(
        hubExecutionTimeout, Duration.ofSeconds(31), Duration.ofMinutes(30), "hubExecutionTimeout");
    requireBounded(maxWait, Duration.ofSeconds(1), Duration.ofHours(2), "maxWait");
    if (hubExecutionTimeout.toSeconds() <= askTimeoutSeconds + 30L) {
      throw new IllegalArgumentException(
          "hubExecutionTimeout must exceed askTimeoutSeconds plus OpenCLI padding");
    }
  }

  private static void requireBounded(Duration value, Duration min, Duration max, String field) {
    if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw new IllegalArgumentException(field + " is outside the supported range");
    }
  }
}
