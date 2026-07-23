package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import java.time.Duration;
import java.util.Objects;

/** Bounded persistence cadence for one Platform worker. */
public record ToolWorkerConfig(
    Duration leaseDuration,
    Duration heartbeatInterval,
    Duration partialFlushInterval,
    int partialBatchBytes,
    int inlineResultBytes,
    int previewBytes) {
  public static final ToolWorkerConfig DEFAULT =
      new ToolWorkerConfig(
          Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMillis(150), 8192, 8192, 1024);

  public ToolWorkerConfig {
    leaseDuration = positive(leaseDuration, "leaseDuration");
    heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
    partialFlushInterval = positive(partialFlushInterval, "partialFlushInterval");
    if (partialBatchBytes <= 0 || inlineResultBytes <= 0 || previewBytes <= 0) {
      throw new IllegalArgumentException("tool worker byte limits must be positive");
    }
  }

  private static Duration positive(Duration value, String name) {
    value = Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
